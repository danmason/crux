(ns crux.ui.routes
  (:require
   [re-frame.core :as rf]))

(def routes
  [""
   ["/attribute-stats"
    {:name :attribute-stats}]
   ["/status"
    {:name :status
     :link-text "Status"
     :controllers
     [{:start #(do
                 (rf/dispatch [:crux.ui.http/fetch-node-status])
                 (rf/dispatch [:crux.ui.http/fetch-node-attribute-stats]))}]}]
   ["/query"
    {:name :query
     :link-text "Query"
     :controllers
     [{:identity #(gensym)
       :start #(rf/dispatch [:crux.ui.http/fetch-query-table])}]}]
   ["/entity"
    {:name :entity
     :link-text "Entity"
     :controllers
     [{:identity #(gensym)
       :start #(rf/dispatch [:crux.ui.http/fetch-entity])}]}]])
