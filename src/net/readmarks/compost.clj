(ns net.readmarks.compost
  (:require [clojure.set :refer [subset? intersection union difference]]
            [clojure.data.priority-map :refer [priority-map-keyfn]]
            [net.readmarks.common.maps :refer :all]
            [clojure.tools.logging :as logging]))

(def component-defaults
  {:requires #{}
   :status   :stopped
   :start    (fn [co _deps] co)
   :stop     identity
   :get      identity
   :this     nil})

(defn dependencies [system]
  (map-vals #(-> % :requires set) system))

(defn- require-ids [deps all-ids ids]
  (when-let [missing (seq (difference ids all-ids))]
    (throw (ex-info (str "Unknown component ids " (pr-str missing))
             {:type         ::error
              :dependencies deps
              :unknown-ids  missing}))))

(defn all-reachable [deps required-ids]
  (let [check-ids #(require-ids deps (key-set deps) %)]
    (logging/debugf "Resolving %s with %s" required-ids deps)
    (check-ids required-ids)
    (loop [result (select-keys deps required-ids)]
      (let [more-ids (difference (set (mapcat second result))
                       (key-set result))]
        (check-ids more-ids)
        (if (seq more-ids)
          (recur (merge result (select-keys deps more-ids)))
          result)))))

(defn call-component [method-key co & args]
  {:pre [(get co method-key) (contains? co :this)]}
  (logging/tracef "Invoking component method %s on %s" method-key co)
  (apply (get co method-key) (:this co) args))

(defn start-component [co deps]
  (assoc co :this (call-component :start co deps)
            :status :started))

(defn stop-component [co]
  (assoc co :this (call-component :stop co)
            :status :stopped))

(defn get-component [co]
  {:pre [(= :started (:status co))]}
  (call-component :get co))

(defn normalize-system [system]
  (let [allowed-keys (key-set component-defaults)]
    (doseq [[k co] system]
      (when-let [unknown-fields (seq (difference (key-set co) allowed-keys))]
        (throw (ex-info "Unknown component field."
                 {:type           ::error
                  :component-id   k
                  :component      co
                  :unknown-fields unknown-fields})))))
  (map-vals #(merge component-defaults %) system))

(defn update-system
  "Updates the system by applying function to components while ensuring given dependencies.
   In case of errors throws ExceptionInfo with :system key containing current system,
   and failed :component (if relevant)."
  [system dependencies update-component]
  (loop [result system
         queue (into (priority-map-keyfn count) dependencies)]
    (logging/debug "System update queue" queue)
    (if (seq queue)
      (let [[co-id deps] (peek queue)]
        (when-not (empty? deps)
          (throw (ex-info "Dependency cycle."
                   {:type   ::error
                    :system result
                    :queue  queue})))
        (recur
          (update result co-id
            (fn [co]
              (try
                (update-component result co)
                (catch Exception ex
                  (throw (ex-info "Cannot update component"
                           {:type      ::error
                            :system    result
                            :component co
                            :queue     queue}
                           ex))))))
          (map-vals #(disj % co-id) (pop queue))))
      result)))

(defn start
  "Starts the system."
  ([system]
   (start system (key-set system)))
  ([system required-ids]
   {:pre [(map? system)]}
   (let [normalized (normalize-system system)
         queue (-> (dependencies normalized)
                 (all-reachable required-ids))]
     (update-system normalized queue
       (fn [sys co]
         (start-component co
           (map-vals get-component ;;; (These values can be cached. Memoize?)
             (select-keys sys (:requires co)))))))))

(defn stop
  "Stops the system."
  ([system]
   (stop system (key-set system)))
  ([system stop-ids]
   {:pre [(map? system)]}
   (let [queue (-> (dependencies system)
                 (reverse-dependencies)
                 (all-reachable stop-ids))]
     (update-system system queue
       (fn [_sys co]
         (stop-component co))))))
