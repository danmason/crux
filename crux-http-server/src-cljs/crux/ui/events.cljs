(ns crux.ui.events
  (:require
   [ajax.edn :as ajax-edn]
   [cljs.reader :as reader]
   [clojure.string :as string]
   [crux.ui.common :as common]
   [crux.ui.http]
   [re-frame.core :as rf]
   [tick.alpha.api :as t]))

(rf/reg-fx
 :scroll-top
 common/scroll-top)

(rf/reg-event-fx
 ::inject-metadata
 (fn [{:keys [db]} [_ title handler]]
   (let [result-meta (some-> (js/document.querySelector
                              (str "meta[title=" title "]"))
                             (.getAttribute "content"))
         ;; this returns an error because the map values include the #object tag
         ;; that read-string doesn't understand. Temporarily, it's wrapper in a
         ;; try catch otherwise this prevents other events from firing i.e. the
         ;; entity http request.
         edn-content (try
                       (reader/read-string result-meta)
                       (catch js/Error e (js/console.error e)))]
     (if edn-content
       {:db (assoc db handler edn-content)}
       (js/console.warn "Metadata not found")))))

(rf/reg-event-db
 ::navigate-to-root-view
 (fn [db [_ view]]
   (-> (assoc-in db [:form-pane :hidden?] false)
       (assoc-in [:form-pane :view] view))))

(rf/reg-event-db
 ::toggle-query-form
 (fn [db [_ visible?]]
   (update-in db [:query-form :visible?] (if (some? visible?) (constantly visible?) not))))

(rf/reg-event-db
 ::query-form-tab-selected
 (fn [db [_ tab]]
   (assoc-in db [:query-form :selected-tab] tab)))

(rf/reg-event-fx
 ::console-tab-selected
 (fn [_ [_ tab]]
   {:dispatch [:navigate tab {} {}]}))

(rf/reg-event-db
 ::toggle-form-pane
 (fn [db [_ & bool]]
   (update-in db [:form-pane :hidden?] #(if (seq bool) (first bool) (not %)))))

(rf/reg-event-db
 ::toggle-form-history
 (fn [db [_ component & bool]]
   (update-in db [:form-pane :history component] #(if (seq bool) (first bool) (not %)))))

(rf/reg-event-db
 ::toggle-show-vt
 (fn [db [_ component bool]]
   (update-in db [:form-pane :show-vt? component] #(if (nil? %) (not bool) (not %)))))

(rf/reg-event-db
 ::toggle-show-tt
 (fn [db [_ component bool]]
   (update-in db [:form-pane :show-tt? component] #(if (nil? %) (not bool) (not %)))))

(rf/reg-event-db
 ::set-form-pane-view
 (fn [db [_ view]]
   (assoc-in db [:form-pane :view] view)))

(rf/reg-event-db
 ::query-table-error
 (fn [db [_ error]]
   (assoc-in db [:query :error] error)))

(rf/reg-event-db
 ::set-query-result-pane-loading
 (fn [db [_ bool]]
   (assoc-in db [:query :result-pane :loading?] bool)))

(rf/reg-event-fx
 ::inject-local-storage
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :query-history (reader/read-string
                                   (.getItem js/window.localStorage "query"))))}))

(defn vec-remove
  [pos coll]
  (vec (concat (subvec coll 0 pos) (subvec coll (inc pos)))))

(rf/reg-event-fx
 ::remove-query-from-local-storage
 (fn [{:keys [db]} [_ idx]]
   (let [query-history (:query-history db)
         updated-history (vec-remove idx query-history)]
     {:db (assoc db :query-history updated-history)
      :local-storage ["query" updated-history]})))

(rf/reg-fx
 :local-storage
 (fn [[k data]]
   (.setItem js/window.localStorage k data)))

(rf/reg-event-fx
 ::go-to-query-view
 (fn [{:keys [db]} [_ {:keys [values]}]]
   (let [{:strs [q vtd vtt ttd ttt]} values
         query-params (->>
                       (->
                        (merge
                         (common/edn->query-params (reader/read-string q))
                         {:valid-time (common/date-time->datetime vtd vtt)
                          :transaction-time (common/date-time->datetime ttd ttt)})
                        (update :limit (fnil identity "100")))
                       (remove #(nil? (second %)))
                       (into {}))
         history-elem (-> (update query-params :valid-time common/iso-format-datetime)
                          (update :transaction-time common/iso-format-datetime))
         current-storage (or (reader/read-string (.getItem js/window.localStorage "query")) [])
         updated-history (conj (into [] (remove #(= history-elem %) current-storage)) history-elem)]
     {:db (assoc db :query-history updated-history)
      :dispatch [:navigate :query {} query-params]
      :local-storage ["query" updated-history]})))

(rf/reg-event-fx
 ::goto-previous-query-page
 (fn [{:keys [db]} _]
   (let [query-params (get-in db [:current-route :query-params])
         offset (js/parseInt (get-in db [:current-route :query-params :offset] 0))
         limit (js/parseInt (get-in db [:current-route :query-params :limit] 100))]
     {:db db
      :dispatch [:navigate :query {} (assoc query-params :offset (-> (- offset limit)
                                                                     (max 0)
                                                                     str))]})))

(rf/reg-event-fx
 ::goto-next-query-page
 (fn [{:keys [db]} _]
   (let [query-params (get-in db [:current-route :query-params])
         offset (js/parseInt (get-in db [:current-route :query-params :offset] 0))
         limit (js/parseInt (get-in db [:current-route :query-params :limit] 100))]
     {:db db
      :dispatch [:navigate :query {} (assoc query-params :offset (-> (+ offset limit) str))]})))

(rf/reg-event-fx
 ::go-to-historical-query
 (fn [{:keys [db]} [_ history-q]]
   (let [{:strs [q valid-time transaction-time]} history-q
         query-params (->>
                       (merge
                        (common/edn->query-params (reader/read-string q))
                        {:valid-time (some-> valid-time)
                         :transaction-time (some-> transaction-time)})
                       (remove #(nil? (second %)))
                       (into {}))]
     {:dispatch [:navigate :query {} query-params]})))

(rf/reg-event-fx
 ::entity-pane-tab-selected
 (fn [{:keys [db]} [_ tab]]
   {:dispatch [:navigate :entity nil (case tab
                                       :document (-> (get-in db [:current-route :query-params])
                                                     (select-keys [:valid-time :transaction-time :eid]))
                                       :history (-> (get-in db [:current-route :query-params])

                                                    (assoc :history true)
                                                    (assoc :with-docs true)
                                                    (assoc :sort-order "desc")))]}))

(rf/reg-event-db
 ::set-entity-result-pane-loading
 (fn [db [_ bool]]
   (assoc-in db [:entity :result-pane :loading?] bool)))

(rf/reg-event-fx
 ::go-to-entity-view
 (fn [{:keys [db]} [_ {{:strs [eid vtd vtt ttd ttt]} :values}]]
   (let [query-params (->>
                       {:valid-time (common/date-time->datetime vtd vtt)
                        :transaction-time (common/date-time->datetime ttd ttt)
                        :eid eid}
                       (remove #(nil? (second %)))
                       (into {}))]
     {:db db
      :dispatch [:navigate :entity nil query-params]})))

(rf/reg-event-db
 ::entity-result-pane-document-error
 (fn [db [_ error]]
   (assoc-in db [:entity :error] error)))

(rf/reg-event-db
 ::set-node-status-loading false
 (fn [db [_ bool]]
   (assoc-in db [:status :loading?] bool)))
