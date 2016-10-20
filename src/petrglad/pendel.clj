(ns petrglad.pendel
  (:require [clojure.set :refer [subset? intersection union difference]]
            [clojure.data.priority-map :refer [priority-map-keyfn]]
            [petrglad.common.maps :refer :all])
  (:import (org.slf4j LoggerFactory)))

(def log (LoggerFactory/getLogger (name (ns-name *ns*))))

(def component-defaults
  {:requires #{}
   :status   :stopped
   :start    (fn [co _deps] co)
   :stop     (fn [co] co)
   :get      identity
   :this     nil})

(defn dependencies [system]
  (map-vals #(-> % :requires (into #{})) system))

(defn- require-ids [system all-ids ids]
  (when-let [missing (seq (difference ids all-ids))]
    (throw (ex-info (str "Unknown component ids " (pr-str missing))
             {:system      system
              :unknown-ids missing}))))

(defn all-requires [system required-ids]
  (let [deps (dependencies system)
        check-ids #(require-ids system (key-set deps) %)]
    (.debug log "Resolving {} with {}" required-ids deps)
    (check-ids required-ids)
    (loop [result (select-keys deps required-ids)]
      (let [more-ids (difference (into #{} (mapcat second result))
                       (key-set result))]
        (check-ids more-ids)
        (if (seq more-ids)
          (recur (merge result (select-keys deps more-ids)))
          result)))))

(defn call-component [method-key co & args]
  {:pre [(get co method-key) (contains? co :this)]}
  (.trace log "Invoking component method {} on {}" method-key co)
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
                 {:component-id   k
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
    (.debug log "System update queue {}" queue)
    (if (seq queue)
      (let [[co-id deps] (peek queue)]
        (when-not (empty? deps)
          (throw (ex-info "Dependency cycle."
                   {:system result
                    :queue  queue})))
        (recur (update result co-id #(update-component result %))
          (map-vals #(disj % co-id) (pop queue))))
      result)))

(defn start
  "Starts the system."
  [system required-ids]
  (let [normalized (normalize-system system)
        requires (all-requires normalized required-ids)]
    (update-system normalized requires
      (fn [sys co]
        (start-component co
          (map-vals get-component ;; (These values can be cached)
            (select-keys sys (:requires co))))))))

(defn stop
  "Stops the system."
  [system]
  (let [provides (reverse-dependencies
                   (dependencies system))]
    (update-system system provides
      (fn [_sys co]
        (stop-component co)))))
