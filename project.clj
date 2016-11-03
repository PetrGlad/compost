(defproject net.readmarks/compost "0.1.0-SNAPSHOT"
  :description "Manage lifecycle of stateful components"
  :url "https://github.com/PetrGlad/compost"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.priority-map "0.0.7"]
                 [org.slf4j/slf4j-api "1.7.21"]]

  :profiles
  {:dev {:dependencies [[org.clojure/core.async "0.2.395"]
                        [org.slf4j/slf4j-simple "1.7.21"]]}})
