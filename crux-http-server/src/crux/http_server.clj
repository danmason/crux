(ns crux.http-server
  "HTTP API for Crux.
  The optional SPARQL handler requires juxt.crux/rdf."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.edn :as edn]
            [clojure.instant :as instant]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [crux.api :as crux]
            [crux.http-server.entity :as entity]
            [crux.http-server.json :as http-json]
            [crux.http-server.query :as query]
            [crux.http-server.status :as status]
            [crux.http-server.util :as util]
            [crux.io :as cio]
            [crux.system :as sys]
            [crux.tx :as tx]
            [crux.tx.conform :as txc]
            [jsonista.core :as json]
            [muuntaja.core :as m]
            [muuntaja.format.core :as mfc]
            reitit.coercion.spec
            [reitit.ring :as rr]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.exception :as re]
            [reitit.ring.middleware.muuntaja :as rm]
            [reitit.swagger :as swagger]
            [ring.adapter.jetty :as j]
            [ring.middleware.params :as p]
            [ring.util.response :as resp]
            [ring.util.time :as rt]
            [clojure.pprint :as pp])
  (:import [com.nimbusds.jose.crypto ECDSAVerifier RSASSAVerifier]
           [com.nimbusds.jose.jwk ECKey JWKSet KeyType RSAKey]
           com.nimbusds.jwt.SignedJWT
           com.fasterxml.jackson.core.JsonGenerator
           [crux.api ICruxAPI NodeOutOfSyncException]
           [java.io ByteArrayOutputStream Closeable IOException]
           java.time.Duration
           crux.codec.EDNId
           org.eclipse.jetty.server.Server))

(defn- add-last-modified [response date]
  (cond-> response
    date (assoc-in [:headers "Last-Modified"] (rt/format-date date))))

(s/def ::entity-tx-spec (s/keys :req-un [(or ::util/eid-edn ::util/eid-json ::util/eid)]
                                :opt-un [::util/valid-time ::util/transact-time]))

(defn- entity-tx [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [eid eid-edn eid-json valid-time transact-time]} (get-in req [:parameters :query])
          eid (or eid-edn eid-json eid)
          db (util/db-for-request crux-node {:valid-time valid-time
                                             :transact-time transact-time})
          {::tx/keys [tx-time] :as entity-tx} (crux/entity-tx db eid)]
      (if entity-tx
        (-> {:status 200
             :body entity-tx}
            (add-last-modified tx-time))
        {:status 404
         :body {:error (str eid " entity-tx not found") }}))))

(defn- ->submit-json-decoder [_]
  (let [decoders {::txc/->doc #(cio/update-if % :crux.db/fn edn/read-string)
                  ::txc/->valid-time (fn [vt-str]
                                       (try
                                         (instant/read-instant-date vt-str)
                                         (catch Exception _e
                                           vt-str)))}]
    (reify
      mfc/Decode
      (decode [_ data _]
        (-> (json/read-value data http-json/crux-object-mapper)
            (update :tx-ops (fn [tx-ops]
                              (->> tx-ops
                                   (mapv (fn [tx-op]
                                           (-> tx-op
                                               (update 0 (fn [op] (keyword "crux.tx" op)))
                                               (txc/conform-tx-op decoders)
                                               (txc/->tx-op))))))))))))

(def ->submit-tx-muuntaja
  (m/create
   (assoc-in (util/->default-muuntaja {:json-encode-fn http-json/camel-case-keys})
             [:formats "application/json" :decoder]
             [->submit-json-decoder])))

(s/def ::tx-ops vector?)
(s/def ::submit-tx-spec (s/keys :req-un [::tx-ops]))

(defn- submit-tx [^ICruxAPI crux-node]
  (fn [req]
    (let [tx-ops (get-in req [:parameters :body :tx-ops])
          {::tx/keys [tx-time] :as submitted-tx} (crux/submit-tx crux-node tx-ops)]
      (-> {:status 202
           :body submitted-tx}
          (add-last-modified tx-time)))))

(s/def ::with-ops? boolean?)
(s/def ::after-tx-id int?)
(s/def ::tx-log-spec (s/keys :opt-un [::with-ops? ::after-tx-id]))

(defn txs->json [txs]
  (mapv #(update % 0 name) txs))

(defn tx-log-json-encode [tx]
  (-> tx
      (cio/update-if :crux.api/tx-ops txs->json)
      (cio/update-if :crux.tx.event/tx-events txs->json)
      (http-json/camel-case-keys)))

(def ->tx-log-muuntaja
  (m/create
   (-> (util/->default-muuntaja {:json-encode-fn tx-log-json-encode})
       (assoc :return :output-stream))))

(defn- tx-log [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [with-ops? after-tx-id]} (get-in req [:parameters :query])]
      (-> {:status 200
           :body {:results (crux/open-tx-log crux-node after-tx-id with-ops?)}
           :return :output-stream}
          (add-last-modified (:crux.tx/tx-time (crux/latest-completed-tx crux-node)))))))

(s/def ::tx-time ::util/transact-time)
(s/def ::sync-spec (s/keys :opt-un [::tx-time ::util/timeout]))

(defn- sync-handler [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [timeout tx-time]} (get-in req [:parameters :query])
          timeout (some-> timeout (Duration/ofMillis))
          last-modified (if tx-time
                          (crux/await-tx-time crux-node tx-time timeout)
                          (crux/sync crux-node timeout))]
      (-> {:status 200
           :body {:crux.tx/tx-time last-modified}}
          (add-last-modified last-modified)))))

(s/def ::await-tx-time-spec (s/keys :req-un [::tx-time] :opt-un [::util/timeout]))

(defn- await-tx-time-handler [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [timeout tx-time]} (get-in req [:parameters :query])
          timeout (some-> timeout (Duration/ofMillis))]
      (let [last-modified (crux/await-tx-time crux-node tx-time timeout)]
        (->
         {:status 200
          :body {:crux.tx/tx-time last-modified}}
         (add-last-modified last-modified))))))

(s/def ::await-tx-spec (s/keys :req-un [::util/tx-id] :opt-un [::util/timeout]))

(defn- await-tx-handler [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [timeout tx-id]} (get-in req [:parameters :query])
          timeout (some-> timeout (Duration/ofMillis))
          {:keys [crux.tx/tx-time] :as tx} (crux/await-tx crux-node {:crux.tx/tx-id tx-id} timeout)]
      (-> {:status 200, :body tx}
          (add-last-modified tx-time)))))

(defn- attribute-stats [^ICruxAPI crux-node]
  (fn [_]
    {:status 200
     :body (crux/attribute-stats crux-node)}))

(s/def ::tx-committed-spec (s/keys :req-un [::util/tx-id]))

(defn- tx-committed? [^ICruxAPI crux-node]
  (fn [req]
    (try
      (let [tx-id (get-in req [:parameters :query :tx-id])]
        {:status 200
         :body {:tx-committed? (crux/tx-committed? crux-node {:crux.tx/tx-id tx-id})}})
      (catch NodeOutOfSyncException e
        {:status 400, :body e}))))

(defn latest-completed-tx [^ICruxAPI crux-node]
  (fn [_]
    (if-let [latest-completed-tx (crux/latest-completed-tx crux-node)]
      {:status 200
       :body latest-completed-tx}
      {:status 404
       :body {:error "No latest-completed-tx found."}})))

(defn latest-submitted-tx [^ICruxAPI crux-node]
  (fn [_]
    (if-let [latest-submitted-tx (crux/latest-submitted-tx crux-node)]
      {:status 200
       :body latest-submitted-tx}
      {:status 404
       :body {:error "No latest-submitted-tx found."}})))

(defn active-queries [^ICruxAPI crux-node]
  (fn [_]
    {:status 200
     :body (crux/active-queries crux-node)}))

(defn recent-queries [^ICruxAPI crux-node]
  (fn [_]
    {:status 200
     :body (crux/recent-queries crux-node)}))

(defn slowest-queries [^ICruxAPI crux-node]
  (fn [_]
    {:status 200
     :body (crux/slowest-queries crux-node)}))

(def ^:private sparql-available?
  (try ; you can change it back to require when clojure.core fixes it to be thread-safe
    (requiring-resolve 'crux.sparql.protocol/sparql-query)
    true
    (catch IOException _
      false)))

(defn sparqql [^ICruxAPI crux-node]
  (fn [req]
    (when sparql-available?
      ((resolve 'crux.sparql.protocol/sparql-query) crux-node req))))

(defn- add-response-format [handler format]
  (fn [req]
    (-> (handler (assoc-in req [:muuntaja/response :format] format))
        (assoc :muuntaja/content-type format))))

(def ^:const default-server-port 3000)

(defrecord HTTPServer [^Server server options]
  Closeable
  (close [_]
    (.stop server)))

(defn valid-jwt?
  "Return true if the given JWS is valid with respect to the given
  signing key."
  [^String jwt ^JWKSet jwks]
  (try
    (let [jws (SignedJWT/parse ^String jwt)
          kid (.. jws getHeader getKeyID)
          jwk (.getKeyByKeyId jwks kid)
          verifier (case (.getValue ^KeyType (.getKeyType jwk))
                     "RSA" (RSASSAVerifier. ^RSAKey jwk)
                     "EC"  (ECDSAVerifier. ^ECKey jwk))]
      (.verify jws verifier))
    (catch Exception _
      false)))

(defn wrap-jwt [handler jwks]
  (fn [request]
    (if-not (valid-jwt? (or (get-in request [:headers "x-amzn-oidc-accesstoken"])
                            (some->> (get-in request [:headers "authorization"])
                                     (re-matches #"Bearer (.*)")
                                     (second)))
                        jwks)
      {:status 401
       :body "JWT Failed to validate"}

      (handler request))))

(defn handle-iae [^crux.IllegalArgumentException ex req]
  {:status 400
   :body (ex-data ex)})

(defn handle-noose [^crux.api.NodeOutOfSyncException ex req]
  ;; TODO NOOSE needs ex-data
  {:status 409
   :body {:error (str ex)}})

(defn handle-muuntaja-decode-error [ex req]
  {:status 400
   :body {:error (str "Malformed " (-> ex ex-data :format pr-str) " request.") }})

(defn wrap-camel-case-params [handler]
  (fn [{:keys [query-params] :as request}]
    (let [kebab-qps (into {} (map (fn [[k v]] [(csk/->kebab-case k) v])) query-params)]
      (handler (assoc request :query-params kebab-qps)))))

(defn- query-list-json-encode [query-states]
  (map (fn [qs]
         (-> qs
             (update :query pr-str)
             http-json/camel-case-keys))
       query-states))

(def ^:private query-list-muuntaja
  (m/create (util/->default-muuntaja {:json-encode-fn query-list-json-encode})))

(defn- create-examples [{:keys [example json-encode-fn] :or {json-encode-fn http-json/camel-case-keys}}]
  (let [baos (ByteArrayOutputStream.)
        tj-writer (transit/writer baos :json {:handlers {EDNId (transit/write-handler "crux/oid" str)}})
        mapper (json/object-mapper {:encode-key-fn true
                                    :decode-key-fn false
                                    :encoders {EDNId (fn [crux-id ^JsonGenerator gen]
                                                       (.writeString gen (str crux-id)))}})]
    {"application/json" (-> (json-encode-fn example)
                            (json/write-value-as-string mapper)
                            (json/read-value mapper))
     "application/edn" (with-out-str (pp/pprint example))
     "application/transit+json" (do (transit/write tj-writer example)
                                    (json/read-value (.toString baos) mapper))}))

(defn- ->crux-router [{{:keys [^String jwks, read-only?]} :http-options
                       :keys [crux-node], :as opts}]
  (let [opts (-> opts (update :http-options dissoc :jwks))
        query-handler {:muuntaja (query/->query-muuntaja opts)
                       :summary "Query"
                       :description "Peform a datalog query"
                       :responses {200 {:examples
                                        (create-examples
                                         {:example '([:user-1 "George" "Example"] [:user-2 "Sally" "Test"] [:user-3 "John" "Smith"])})}}
                       :get {:handler (query/data-browser-query opts)
                             :parameters {:query ::query/query-params}}
                       :post {:handler (query/data-browser-query opts)
                              :parameters {:query ::query/query-params
                                           :body ::query/body-params}}}]
    (rr/router [["/" {:no-doc true
                      :get (fn [_] (resp/redirect "/_crux/query"))}]
                ["/_crux"
                 ["/status" {:muuntaja (status/->status-muuntaja opts)
                             :summary "Status"
                             :description "Get status information from the node"
                             :get (status/status opts)
                             :responses {200 {:examples
                                              (create-examples
                                               {:example {:crux.version/version "20.09-1.11.1-beta-SNAPSHOT",
	                                                  :crux.version/revision nil, :crux.index/index-version 13,
	                                                  :crux.doc-log/consumer-state nil,
	                                                  :crux.tx-log/consumer-state nil,
	                                                  :crux.kv/kv-store "crux.rocksdb.RocksKv",
	                                                  :crux.kv/estimate-num-keys 3,
	                                                  :crux.kv/size 132665}})}}}]
                 ["/entity" {:muuntaja (entity/->entity-muuntaja opts)
                             :summary "Entity"
                             :description "Get information about a particular entity"
                             :get (entity/entity-state opts)
                             :parameters {:query ::entity/query-params}
                             :responses {200 {:examples
                                              (create-examples
                                               {:example {:crux.db/id :hello :age 20}
                                                :json-encode-fn identity})}}}]
                 ["/query" query-handler]
                 ["/query.csv" (assoc query-handler :middleware [[add-response-format "text/csv"]] :no-doc true)]
                 ["/query.tsv" (assoc query-handler :middleware [[add-response-format "text/tsv"]] :no-doc true)]
                 ["/entity-tx" {:get (entity-tx crux-node)
                                :summary "Entity Tx"
                                :description "Get transactional information an particular entity"
                                :parameters {:query ::entity-tx-spec}
                                :responses {200 {:examples
                                                 (create-examples
                                                  {:example {:crux.db/id #crux/id "5aeebab117b892fa42002146e4c62be676bc4621",
	                                                     :crux.db/content-hash #crux/id "99747f80357c336ee5efd073c878313bf85b07f9",
	                                                     :crux.db/valid-time #inst "2020-10-16T14:29:35.664-00:00",
	                                                     :crux.tx/tx-time #inst "2020-10-16T14:29:35.664-00:00",
	                                                     :crux.tx/tx-id 5}})}}}]
                 ["/attribute-stats" {:get (attribute-stats crux-node)
                                      :summary "Attribute Stats"
                                      :description "Get frequencies of indexed attributes"
                                      :muuntaja (m/create (util/->default-muuntaja {:json-encode-fn identity}))
                                      :responses {200 {:examples
                                                       (create-examples
                                                        {:example {:age 1,
	                                                           :firstName 1,
	                                                           :lastName 1,
	                                                           :crux.db/id 3,
	                                                           :first-name 2,
	                                                           :last-name 2}
                                                         :json-encode-fn identity})}}}]
                 ["/sync" {:get (sync-handler crux-node)
                           :summary "Sync"
                           :description "Wait until the Kafka consumer’s lag is back to 0"
                           :parameters {:query ::sync-spec}
                           :responses {200 {:examples
                                            (create-examples
                                             {:example {:crux.tx/tx-time #inst "2020-10-16T14:29:35.664-00:00"}})}}}]
                 ["/await-tx" {:get (await-tx-handler crux-node)
                               :summary "Await Tx"
                               :description "Wait until the node has indexed a transaction at or past the supplied tx-id"
                               :parameters {:query ::await-tx-spec}
                               :responses {200 {:examples
                                                (create-examples
                                                 {:example {:crux.tx/tx-id 5, :crux.tx/tx-time #inst "2020-10-16T14:29:35.664-00:00"}})}}}]
                 ["/await-tx-time" {:get (await-tx-time-handler crux-node)
                                    :summary "Await Tx Time"
                                    :description "Wait until the node has indexed a transaction that is past the supplied tx-time"
                                    :parameters {:query ::await-tx-time-spec}
                                    :responses {200 {:examples
                                                     (create-examples
                                                      {:example {:crux.tx/tx-time #inst "2020-10-16T14:29:35.664-00:00"}})}}}]
                 ["/tx-log" {:get (tx-log crux-node)
                             :summary "Tx Log"
                             :description "Get a list of all transactions"
                             :muuntaja ->tx-log-muuntaja
                             :parameters {:query ::tx-log-spec}
                             :responses {200 {:examples
                                              (create-examples
                                               {:example '({:crux.tx/tx-id 0,
	                                                    :crux.tx/tx-time #inst "2020-10-16T09:02:43.429-00:00",
	                                                    :crux.tx.event/tx-events [[:crux.tx/put
	  			                                                       #crux/id "83bed47ace572cb94c2f137f58bce73b9b7d0039"
				                                                       #crux/id "f441402b3c5d37365203947aabe85cf471498bf0"
				                                                       #inst "2020-06-20T20:05:50.000-00:00"]]}
	                                                   {:crux.tx/tx-id 1,
	                                                    :crux.tx/tx-time #inst "2020-10-16T09:28:27.785-00:00",
	                                                    :crux.tx.event/tx-events [[:crux.tx/put
	  			                                                       #crux/id "83bed47ace572cb94c2f137f58bce73b9b7d0039"
				                                                       #crux/id "f441402b3c5d37365203947aabe85cf471498bf0"
				                                                       #inst "2020-06-20T20:05:50.000-00:00"]]})
                                                :json-encode-fn (fn [txs] (map tx-log-json-encode txs))})}}}]
                 ["/submit-tx" {:muuntaja ->submit-tx-muuntaja
                                :summary "Submit Tx"
                                :description "Takes a vector of transactions - Writes to the node"
                                :post (if read-only?
                                        (fn [_] {:status 403
                                                 :body "forbidden: read-only HTTP node"})
                                        (submit-tx crux-node))
                                :parameters {:body ::submit-tx-spec}
                                :responses {200 {:examples
                                                 (create-examples
                                                  {:example  {:crux.tx/tx-id 6, :crux.tx/tx-time #inst "2020-10-19T09:21:29Z"}})}}}]
                 ["/tx-committed" {:get (tx-committed? crux-node)
                                   :summary "Tx Committed"
                                   :description "Checks if a submitted tx was successfully committed"
                                   :parameters {:query ::tx-committed-spec}
                                   :responses {200 {:examples
                                                    (create-examples
                                                     {:example {:tx-committed? true}})}}}]
                 ["/latest-completed-tx" {:get (latest-completed-tx crux-node)
                                          :summary "Latest Completed Tx"
                                          :description "Get the latest transaction to have been indexed by this node"
                                          :responses {200 {:examples
                                                           (create-examples
                                                            {:example {:crux.tx/tx-id 5, :crux.tx/tx-time #inst "2020-10-16T14:29:35.664-00:00"}})}}}]
                 ["/latest-submitted-tx" {:get (latest-submitted-tx crux-node)
                                          :summary "Latest Submitted Tx"
                                          :description "Get the latest transaction to have been submitted to this cluster"
                                          :responses {200 {:examples
                                                           (create-examples
                                                            {:example {:crux.tx/tx-id 5}})}}}]
                 ["/active-queries" {:get (active-queries crux-node)
                                     :summary "Active Queries"
                                     :description "Get a list of currently running queries"
                                     :muuntaja query-list-muuntaja
                                     :responses {200 {:examples
                                                      (create-examples
                                                       {:example '({:status :in-progress
	                                                            :query-id "ae17c599-dcd2-47ee-bebd-47a3122f8d34",
	                                                            :query {:find [e first-name], :where [[e :first-name first-name] [e :last-name "Tutorial"]]},
	                                                            :started-at #inst "2020-10-16T15:48:52.656-00:00",
	                                                            :finished-at nil
	                                                            :error nil})
                                                        :json-encode-fn query-list-json-encode})}}}]
                 ["/recent-queries" {:get (recent-queries crux-node)
                                     :summary "Recent Queries"
                                     :description "Get a list of recently completed/failed queries"
                                     :muuntaja query-list-muuntaja
                                     :responses {200 {:examples
                                                      (create-examples
                                                       {:example '({:status :completed,
	                                                            :query-id "ae17c599-dcd2-47ee-bebd-47a3122f8d34",
	                                                            :query {:find [e first-name], :where [[e :first-name first-name] [e :last-name "Tutorial"]]},
	                                                            :started-at #inst "2020-10-16T15:48:52.656-00:00",
	                                                            :finished-at #inst "2020-10-16T15:48:52.835-00:00",
	                                                            :error nil})
                                                        :json-encode-fn query-list-json-encode})}}}]
                 ["/slowest-queries" {:get (slowest-queries crux-node)
                                      :summary "Slowest Queries"
                                      :description "Get a list of slowest completed/failed queries ran on the node"
                                      :muuntaja query-list-muuntaja
                                      :responses {200 {:examples
                                                       (create-examples
                                                        {:example '({:status :completed,
	                                                             :query-id "ae17c599-dcd2-47ee-bebd-47a3122f8d34",
	                                                             :query {:find [e first-name], :where [[e :first-name first-name] [e :last-name "Tutorial"]]},
	                                                             :started-at #inst "2020-10-16T15:48:52.656-00:00",
	                                                             :finished-at #inst "2020-10-16T15:48:52.835-00:00",
	                                                             :error nil})
                                                         :json-encode-fn query-list-json-encode})}}}]
                 ["/sparql" {:get (sparqql crux-node)
                             :post (sparqql crux-node)
                             :no-doc true}]]
                ["/swagger.json"
                 {:get {:no-doc true
                        :swagger {:info {:title "Crux API"}}
                        :handler (swagger/create-swagger-handler)
                        :muuntaja (m/create (assoc (util/->default-muuntaja {}) :default-format "application/json"))}}]]

               {:data
                {:muuntaja (m/create (util/->default-muuntaja {:json-encode-fn http-json/camel-case-keys}))
                 :coercion reitit.coercion.spec/coercion
                 :middleware (cond-> [p/wrap-params
                                      wrap-camel-case-params
                                      rm/format-negotiate-middleware
                                      rm/format-response-middleware
                                      (re/create-exception-middleware
                                       (merge re/default-handlers
                                              {crux.IllegalArgumentException handle-iae
                                               crux.api.NodeOutOfSyncException handle-noose
                                               :muuntaja/decode handle-muuntaja-decode-error}))
                                      rm/format-request-middleware
                                      rrc/coerce-response-middleware
                                      rrc/coerce-request-middleware]
                               jwks (conj #(wrap-jwt % (JWKSet/parse jwks))))}})))

;; entry point for users including our handler in their own server
(defn ->crux-handler [crux-node http-options]
  (rr/routes
   (rr/ring-handler (->crux-router {:crux-node crux-node
                                    :http-options http-options}))
   (rr/create-resource-handler {:path "/"})))

(alter-meta! #'->crux-handler assoc :arglists '([crux-node {:keys [jwks read-only? server-label]}]))

(defn ->server {::sys/deps {:crux-node :crux/node}
                ::sys/args {:port {:spec ::sys/nat-int
                                   :doc "Port to start the HTTP server on"
                                   :default default-server-port}
                            :read-only? {:spec ::sys/boolean
                                         :doc "Whether to start the Crux HTTP server in read-only mode"
                                         :default false}
                            :jwks {:spec ::sys/string
                                   :doc "JWKS string to validate against"}
                            :server-label {:spec ::sys/string}}}
  [{:keys [crux-node port] :as options}]
  (let [server (j/run-jetty (rr/ring-handler (->crux-router {:crux-node crux-node
                                                             :http-options (dissoc options :crux-node)})
                                             (rr/routes
                                              (rr/create-resource-handler {:path "/"})
                                              (rr/create-default-handler)))
                            {:port port
                             :join? false})]
    (log/info "HTTP server started on port: " port)
    (->HTTPServer server options)))
