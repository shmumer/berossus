(ns berossus.rocks.your.data.server
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [berossus.rocks.your.data.api :refer [api-routes]]
            [berossus.rocks.your.data.config :refer [get-config]]
            [berossus.rocks.your.data.middleware
             :refer [wrap-exception wrap-export wrap-service]]
            [org.httpkit.server :refer [run-server]]
            [clojure.tools.nrepl.server
             :refer [start-server stop-server]]
            [taoensso.timbre :as t]
            [selmer.parser]
            [ring.middleware.keyword-params
             :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.reload :as reload])
  (:gen-class))

(defonce http-server (atom nil))
(defonce nrepl-server (atom nil))

(defn all-routes []
  [(api-routes)])

(def app (-> (apply routes (all-routes))
             (wrap-export)
             (wrap-exception)
             (wrap-keyword-params)
             (wrap-params)))

(defn start-nrepl []
  (t/info "starting nrepl on port 7888")
  (reset! nrepl-server (start-server :port 7888))
  (t/info "started nrepl on port 7888"))

(defn stop-nrepl []
  ;; are you sure bro?
  (@nrepl-server)
  (t/warn "Stopped nrepl"))

(defn start-web []
  (selmer.parser/cache-off!)
  (let [handler (if (get-config :dev)
                  (do
                    (t/info "In dev mode, wrapping reload")
                    (reload/wrap-reload #'app))
                  app)]
    (t/info "starting http-kit on port 3000")
    (reset! http-server (run-server handler {:port 3000}))
    (t/info "started http-kit")))

(defn stop-web []
  (@http-server)
  (t/info "Stopped http-kit"))

(defn -main [& args]
  (start-nrepl)
  (start-web)
  (t/info "Everybody's fired up from main"))
