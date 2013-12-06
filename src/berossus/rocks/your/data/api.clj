(ns berossus.rocks.your.data.api
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [berossus.rocks.your.data.db :refer [ensure-db]]
            [berossus.rocks.your.data.middleware :refer [wrap-service]]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]))

(defn conn [dburi]
  (let [_ (ensure-db dburi)]
    (d/connect dburi)))

(defn dburi-from-request [request]
  (let [service (:service (:params request))
        dburi   (get-in request [:services (keyword service)])]
    dburi))

(defn transactor [request]
  (let [{:keys [transactee]} (:params request)
        db-uri  (dburi-from-request request)
        db-conn (conn db-uri)]
    {:data {:result @(d/transact db-conn (read-string transactee))}}))

(defn query [request]
  (let [{:keys [query limit offset args]} (:params request)
        db-uri  (dburi-from-request request)
        db-conn (conn db-uri)
        results (d/q (read-string query) (d/db db-conn))
        num-results (count results)]
    {:data
     {:result results :count num-results}
     :template "templates/dump.html"}))

(defn api-routes []
  (routes
   (GET  "/api/v1/:service/" {params :params} (wrap-service query))
   (POST "/api/v1/:service/" {params :params} (wrap-service transactor))))
