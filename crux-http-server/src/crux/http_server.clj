(ns crux.http-server
  "HTTP API for Crux.
  The optional SPARQL handler requires juxt.crux/rdf."
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [crux.api :as crux]
            [crux.http-server.entity :as entity]
            [crux.http-server.query :as query]
            [crux.http-server.status :as status]
            [crux.http-server.util :as util]
            [crux.io :as cio]
            [crux.system :as sys]
            [crux.tx :as tx]
            [reitit.coercion :as coercion]
            reitit.coercion.spec
            [reitit.ring :as rr]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as rm]
            [ring.adapter.jetty :as j]
            [ring.middleware.params :as p]
            [ring.util.response :as resp]
            [ring.util.time :as rt])
  (:import [com.nimbusds.jose.crypto ECDSAVerifier RSASSAVerifier]
           [com.nimbusds.jose.jwk ECKey JWKSet KeyType RSAKey]
           com.nimbusds.jwt.SignedJWT
           [crux.api ICruxAPI NodeOutOfSyncException]
           [java.io Closeable IOException]
           java.time.Duration
           org.eclipse.jetty.server.Server))

(defn- exception-response [status ^Exception e]
  {:status status
   :body (Throwable->map e)})

(defn- wrap-exception-handling [handler]
  (fn [request]
    (try
      (try
        (handler request)
        (catch Exception e
          (if (instance? IllegalArgumentException e)
            (exception-response 400 e) ;; Valid edn, invalid content
            (do (log/error e "Exception while handling request:" (cio/pr-edn-str request))
                (exception-response 500 e))))) ;; Valid content; something internal failed, or content validity is not properly checked
      (catch Exception e
        (exception-response 400 e))))) ;;Invalid edn

(defn- wrap-coercion-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (let [data (ex-data e)]
          (if (= ::coercion/request-coercion (:type data))
            (exception-response 400 (ex-info "Spec assertion failed" (coercion/encode-error data)))
            (throw e)))))))

(defn- add-last-modified [response date]
  (cond-> response
    date (assoc-in [:headers "Last-Modified"] (rt/format-date date))))

(s/def ::entity-tx-spec (s/keys :req-un [::util/eid] :opt-un [::util/valid-time ::util/transaction-time]))

(defn- entity-tx [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [eid valid-time transaction-time]} (get-in req [:parameters :query])
          db (util/db-for-request crux-node {:valid-time valid-time
                                             :transact-time transaction-time})
          {:keys [crux.tx/tx-time] :as entity-tx} (crux/entity-tx db eid)]
      (if entity-tx
        (-> {:status 200
             :body entity-tx}
            (add-last-modified tx-time))
        {:status 404
         :body {:error (str eid " entity-tx not found") }}))))

(s/def ::transact-spec vector?)

(defn- transact [^ICruxAPI crux-node]
  (fn [req]
    (let [tx-ops (get-in req [:parameters :body])
          {:keys [crux.tx/tx-time] :as submitted-tx} (crux/submit-tx crux-node tx-ops)]
      (->
       {:status 202
        :body submitted-tx}
       (add-last-modified tx-time)))))

(s/def ::with-ops? boolean?)
(s/def ::after-tx-id int?)
(s/def ::tx-log-spec (s/keys :opt-un [::with-ops? ::after-tx-id]))

;; TODO: Could add from date parameter.
(defn- tx-log [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [with-ops? after-tx-id]} (get-in req [:parameters :query])
          result (crux/open-tx-log crux-node after-tx-id with-ops?)]
      (->
       {:status 200
        :body (iterator-seq result)
        :return :output-stream}
       (add-last-modified (:crux.tx/tx-time (crux/latest-completed-tx crux-node)))))))

(s/def ::tx-time ::util/transaction-time)
(s/def ::sync-spec (s/keys :opt-un [::tx-time ::util/timeout]))

(defn- sync-handler [^ICruxAPI crux-node]
  (fn [req]
    (let [{:keys [timeout tx-time]} (get-in req [:parameters :query])
          timeout (some-> timeout (Duration/ofMillis))]
      (let [last-modified (if tx-time
                            (crux/await-tx-time crux-node tx-time timeout)
                            (crux/sync crux-node timeout))]
        (->
         {:status 200
          :body {:crux.tx/tx-time last-modified}}
         (add-last-modified last-modified))))))

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
          timeout (some-> timeout (Duration/ofMillis))]
      (let [{:keys [crux.tx/tx-time] :as tx} (crux/await-tx crux-node {:crux.tx/tx-id tx-id} timeout)]
        (->
         {:status 200
          :body tx}
         (add-last-modified tx-time))))))

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
        (exception-response 400 e)))))

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

(defn- handler [{:keys [crux-node ::read-only?] :as options}]
  (let [query-muuntaja (query/->query-muuntaja options)]
    [["/" {:get (fn [_] (resp/redirect "/_crux/query"))}]
     ["/_crux/status" {:get (status/status options)}]
     ["/_crux/entity" {:muuntaja (entity/->entity-muuntaja options)
                 :get (entity/entity-state options)
                 :parameters {:query ::entity/query-params}}]
     ["/_crux/query" {:muuntaja query-muuntaja
                :get (query/data-browser-query options)
                :parameters {:query ::query/query-params}}]
     ["/_crux/query.csv" {:muuntaja query-muuntaja
                    :get (query/data-browser-query options)
                    :parameters {:query ::query/query-params}
                    :middleware [[add-response-format "text/csv"]]}]
     ["/_crux/query.tsv" {:muuntaja query-muuntaja
                    :get (query/data-browser-query options)
                    :parameters {:query ::query/query-params}
                    :middleware [[add-response-format "text/tsv"]]}]
     ["/_crux/entity-tx" {:get (entity-tx crux-node)
                    :parameters {:query ::entity-tx-spec}}]
     ["/_crux/attribute-stats" {:get (attribute-stats crux-node)}]
     ["/_crux/sync" {:get (sync-handler crux-node)
               :parameters {:query ::sync-spec}}]
     ["/_crux/await-tx" {:get (await-tx-handler crux-node)
                   :parameters {:query ::await-tx-spec}}]
     ["/_crux/await-tx-time" {:get (await-tx-time-handler crux-node)
                        :parameters {:query ::await-tx-time-spec}}]
     ["/_crux/tx-log" {:get {:muuntaja util/output-stream-muuntaja
                       :handler (tx-log crux-node)
                       :parameters {:query ::tx-log-spec}}
                 :post {:handler (if read-only?
                                   (fn [_] {:status 403
                                            :body "forbidden: read-only HTTP node"})
                                   (transact crux-node))
                        :parameters {:body ::transact-spec}}}]
     ["/_crux/tx-committed" {:get (tx-committed? crux-node)
                       :parameters {:query ::tx-committed-spec}}]
     ["/_crux/latest-completed-tx" {:get (latest-completed-tx crux-node)}]
     ["/_crux/latest-submitted-tx" {:get (latest-submitted-tx crux-node)}]
     ["/_crux/active-queries" {:get (active-queries crux-node)}]
     ["/_crux/recent-queries" {:get (recent-queries crux-node)}]
     ["/_crux/slowest-queries" {:get (slowest-queries crux-node)}]
     ["/_crux/sparql" {:get (sparqql crux-node)
                  :post (sparqql crux-node)}]]))

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
  [{:keys [crux-node port read-only? ^String jwks] :as options}]
  (let [server (j/run-jetty
                (rr/ring-handler
                 (rr/router
                  (handler {:node-options (dissoc options :jwks :crux-node)
                            :crux-node crux-node
                            ::read-only? read-only?})
                  {:data
                   {:muuntaja util/default-muuntaja
                    :coercion reitit.coercion.spec/coercion
                    :middleware
                    (cond->
                        [rm/format-middleware
                         wrap-exception-handling
                         wrap-coercion-exception
                         p/wrap-params
                         rrc/coerce-request-middleware]
                      jwks (conj #(wrap-jwt % (JWKSet/parse jwks))))}})
                 (rr/routes
                  (rr/create-resource-handler {:path "/"})
                  (rr/create-default-handler)))
                {:port port
                 :join? false})]
    (log/info "HTTP server started on port: " port)
    (->HTTPServer server options)))
