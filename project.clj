(defproject berossus "0.0.1"
  :description "HTTP API service for Datomic"
  :url "http://github.com/bitemyapp/berossus/"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repl-options {:port 7667}
  :main berossus.rocks.your.data.server
  :dependencies [[org.clojure/clojure "1.5.1"]
                 ;; exclude and use your own as necessary
                 [com.datomic/datomic-pro "0.8.4270"]
                 [http-kit "2.1.13"]
                 [compojure "1.1.5"]
                 [ring/ring-devel "1.1.0"]
                 [ring-mock "0.1.5"]
                 [selmer "0.5.4"]
                 [environ "0.4.0"]
                 [com.taoensso/timbre "2.6.2" :exclusions [clj-stacktrace]]
                 [clj-stacktrace "0.2.7"] ;; Fixes NPE
                 [liberator "0.10.0"]])
