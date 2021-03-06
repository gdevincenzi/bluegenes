(ns bluegenes.pages.results.events
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [re-frame.core :refer [reg-event-db reg-event-fx reg-fx dispatch subscribe]]
            [cljs.core.async :refer [put! chan <! close!]]
            [imcljs.fetch :as fetch]
            [imcljs.path :as path]
            [imcljs.query :as q]
            [bluegenes.interceptors :refer [clear-tooltips]]
            [accountant.core :as accountant]
            [bluegenes.interceptors :refer [abort-spec]]
            [cljs-time.core :as time]
            [cljs-time.coerce :as time-coerce]))


(comment
  "To automatically display some results in this section (the Results / List Analysis page),
  fire the :results/history+ event with a package that represents a query, like so:"
  (dispatch [:results/history+ {:source :flymine
                                :type :query
                                :value {:title "Appears in Breadcrumb"
                                        :from "Gene"
                                        :select ["Gene.symbol"]
                                        :where {:path "Gene.symbol" :op "=" :value "runt"}}}])
  "
  Doing so will automatically direct the browser to the /results/[latest-history-index] route
  which in turn fires the [:results/load-history latest-history-index]. This triggers the fetching
  of enrichment results and boots the im-table
  ")

(defn build-matches-query [query path-constraint identifier]
  (update-in (js->clj (.parse js/JSON query) :keywordize-keys true) [:where]
             conj {:path path-constraint
                   :op "ONE OF"
                   :values [identifier]}))

(reg-event-db
  :save-summary-fields
  (fn [db [_ identifier response]]
    (assoc-in db [:results :summary-values identifier] response)))

(reg-fx
  :get-summary-values
  (fn [[identifier c]]
    (go (dispatch [:save-summary-fields identifier (<! c)]))))

(reg-event-fx
  :results/get-item-details
  (fn [{db :db} [_ identifier path-constraint]]
    (let [source (get-in db [:results :package :source])
          model (get-in db [:mines source :service :model])
          classname (keyword (path/class model path-constraint))
          summary-fields (get-in db [:assets :summary-fields source classname])
          service (get-in db [:mines source :service])
          summary-chan (fetch/rows
                         service
                         {:from classname
                          :select summary-fields
                          :where [{:path (last (clojure.string/split path-constraint "."))
                                   :op "="
                                   :value identifier}]})]
      {:db (assoc-in db [:results :summary-chan] summary-chan)
       :get-summary-values [identifier summary-chan]})))

; Fire this event to append a query package to the BlueGenes list analysis history
; and then route the browser to a URL that displays the last package in history (the one we just added)
(reg-event-fx
  :results/history+
  (abort-spec bluegenes.specs/im-package)
  (fn [{db :db} [_ {:keys [source value type] :as package}]]
    {:db (-> db
             (update-in [:results :history] conj package)
             (assoc-in [:results :queries (:title value)]
                       (assoc package :last-executed (time-coerce/to-long (time/now)))))
     ; By navigating to the URL below, the :results/load-index (directly below) event is fired;
     :navigate (str "/results/" (:title value))}))


; Load one package at a particular index from the list analysis history collection
(reg-event-fx
  :results/load-history
  [(clear-tooltips)] ; This clears any existing tooltips on the screen when the event fires
  (fn [{db :db} [_ title]]
    (let [
          ; Get the details of the current package
          {:keys [source type value] :as package} (get-in db [:results :queries title])
          ; Get the current model
          model (get-in db [:mines source :service :model])
          mine-name (get-in db [:mines source])
          service (get-in db [:mines source :service])]
      ; Store the values in app-db.
      ; TODO - 99% of this can be factored out by passing the package to the :enrichment/enrich and parsing it there
      {:db (update db :results assoc
                   :table nil
                   :query value
                   :package package
                   ; The index is used to highlight breadcrumbs
                   :history-index title
                   :query-parts (q/group-views-by-class model value)
                   ; Clear the enrichment results before loading any new ones
                   :enrichment-results nil)
       :dispatch-n [
                    ; Fire the enrichment event (see the TODO above)
                    [:enrichment/enrich]
                    [:im-tables/load [:results :table] {:service service
                                                        :query value
                                                        :settings {:pagination {:limit 10}
                                                                   :links {:vocab {:mine (name source)}
                                                                           :url (fn [vocab] (str "#/reportpage/"
                                                                                                 (:mine vocab) "/"
                                                                                                 (:class vocab) "/"
                                                                                                 (:objectId vocab)))}}}]]})))

(reg-event-fx
  :fetch-ids-from-query
  (fn [world [_ service query what-to-enrich]]
    {:im-chan {:chan (fetch/rows service query)
               :on-success [:success-fetch-ids]}}))

(reg-event-fx
  :success-fetch-ids
  (fn [{db :db} [_ results]]
    {:db (assoc-in db [:results :ids-to-enrich] (flatten (:results results)))
     :dispatch [:enrichment/run-all-enrichment-queries]}))



(defn service [db mine-kw]
  (get-in db [:mines mine-kw :service]))

(reg-event-fx
  :results/run
  (fn [{db :db} [_ params]]
    (let [enrichment-chan (fetch/enrichment (service db (get-in db [:results :package :source])) params)]
      {:db (assoc-in db [:results
                         :enrichment-results
                         (keyword (:widget params))] nil)
       :enrichment/get-enrichment [(:widget params) enrichment-chan]})))
