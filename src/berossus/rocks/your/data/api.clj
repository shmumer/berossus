(ns berossus.rocks.your.data.api
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [berossus.rocks.your.data.db :refer [ensure-db]]
            [berossus.rocks.your.data.middleware :refer [wrap-service]]
            [berossus.rocks.your.data.services :refer [registered]]
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
        limit (or limit 10)
        offset (or offset 0)
        paginated (take limit (drop offset results))
        num-results (count results)]
    {:data
     {:result paginated :count num-results}
     :template "templates/dump.html"}))

(defn list-services [request]
  (let [services (:services request)]
    {:data {:result services}
     :template "templates/dump.html"}))

(defn query-services [request]
  (let [service (:service request)]
    {:data {:result service}
     :template "templates/dump.html"}))

(defn create-service
  "Creates a service with an alias key for
  a dburi value. Ensures the database exists."
  [request]
  (let [{:keys [service dburi]} (:params request)
        ensured (ensure-db dburi)
        kw      (keyword service)
        new-reg (swap! registered :assoc kw dburi)]
    {:data {:result new-reg}
     :template "templates/dump.html"}))

(defn delete-service
  "Deletes database in non-production,
  merely unregisters service in production"
  [request]
  (let [service (:service request)
        dburi   ((keyword service) @registered)
        dev?    (get-config :dev)
        new-reg (swap! registered dissoc (keyword service))]
    (when dev?
      (d/delete-database dburi))
    {:data {:result new-reg}
     :template "templates/dump.html"}))

(defn api-routes []
  (routes
   (GET     "/api/v1/services/" {params :params} (wrap-service list-services))
   (GET     "/api/v1/services/:service/" {params :params} (wrap-service query-services))
   (POST    "/api/v1/services/:service/" {params :params} create-service)
   (DELETE  "/api/v1/services/:service/" {params :params} (wrap-service delete-service))

   (GET  "/api/v1/:service/" {params :params} (wrap-service query))
   (POST "/api/v1/:service/" {params :params} (wrap-service transactor))))
