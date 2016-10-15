(defproject pendel "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :plugins [[jonase/eastwood "0.2.3"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.priority-map "0.0.7"]
                 [org.slf4j/slf4j-api "1.7.21"]]

  :profiles
  {:dev {:dependencies [[org.slf4j/slf4j-simple "1.7.21"]]}})
