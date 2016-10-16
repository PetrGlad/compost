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

(defn all-requires [system required-ids]
  (let [deps (dependencies system)]
    (.debug log "Resolving {} with {}" required-ids deps)
    (loop [result (select-keys deps required-ids)]
      (let [more-ids (difference (into #{} (mapcat second result))
                       (key-set result))]
        (when-let [unsatisfied (seq (difference more-ids (key-set deps)))]
          (throw (ex-info "Unknown component ids."
                   {:components  result
                    :unknown-ids unsatisfied})))
        (if (seq more-ids)
          (recur (merge result (select-keys deps more-ids)))
          result)))))

(defn call-component [method-key co & args]
  (.trace log "Invoking component method {} on {}" method-key co)
  (apply (method-key co) (:this co) args))

(defn start-component [co deps]
  (assoc co :this (call-component :start co deps)
            :status :started))

(defn stop-component [co]
  (assoc co :this (call-component :stop co)
            :status :stopped))

(defn normalize-system [system]
  (let [allowed-keys (key-set component-defaults)]
    (doseq [[k co] system]
      (when-let [unknown-fields (seq (difference (key-set co) allowed-keys))]
        (throw (ex-info "Unknown component field."
                 {:component-id   k
                  :component      co
                  :unknown-fields unknown-fields})))))
  (map-vals #(merge component-defaults %) system))

(defn start
  "Starts the system"
  [system required-ids]
  (let [normalized (normalize-system system)
        requires (all-requires normalized required-ids)]
    (loop [result normalized
           started-values {}
           queue (into (priority-map-keyfn count) requires)]
      (.debug log "To be started {}" queue)
      (if (seq queue)
        (let [[co-id deps] (peek queue)]
          (when-not (empty? deps)
            (throw (ex-info "Dependency cycle."
                     {:components    result
                      :to-be-started queue})))
          (let [started (start-component (get result co-id)
                          (select-keys started-values (get requires co-id)))]
            (recur (assoc result co-id started)
              (assoc started-values co-id (call-component :get started))
              (map-vals #(disj % co-id) (pop queue)))))
        result))))

(defn stop
  "Stops the system"
  [system]
  (let [requires (dependencies system) ;; TODO (optimization) Select only started components
        provides (reverse-dependencies requires)]
    (loop [result system
           queue (into (priority-map-keyfn count) provides)]
      (.debug log "To be stopped {}" queue)
      (if (seq queue)
        (let [[co-id deps] (peek queue)]
          (when-not (empty? deps)
            (throw (ex-info "Dependency cycle."
                     {:components result
                      :queue      queue})))
          (recur (assoc result co-id (stop-component (get result co-id)))
                 (map-vals #(disj % co-id) (pop queue))))
        result))))
