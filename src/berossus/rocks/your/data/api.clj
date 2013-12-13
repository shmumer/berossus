(ns berossus.rocks.your.data.api
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [berossus.rocks.your.data.config :refer [get-config]]
            [berossus.rocks.your.data.db :refer [ensure-db]]
            [berossus.rocks.your.data.middleware :refer [inject-services wrap-service]]
            [berossus.rocks.your.data.services :refer [registered]]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]))

;; Move conn and get-db to db.clj
(defn conn [dburi]
  (let [_ (ensure-db dburi)]
    (d/connect dburi)))

(defn get-db
  "Gets a database value, defaulting db-uri, and ensures database exists"
  ([db-uri]
     (d/db (conn db-uri))))

(defn dburi-from-request [request]
  (let [service (:service (:params request))
        dburi   (get-in request [:services (keyword service)])]
    dburi))

(defn transactor [request]
  (let [{:keys [transactee]} (:params request)
        db-uri  (dburi-from-request request)
        db-conn (conn db-uri)]
    {:data {:result @(d/transact db-conn (read-string transactee))}}))

(defn string->number [s]
  (if (string? s)
    (and (seq s) (Integer. s))
    s))

(defn query [request]
  (let [{:keys [query limit offset args]} (:params request)
        db-uri  (dburi-from-request request)
        db (get-db db-uri)
        query-with-args (or (and args
                              (concat [query db] (clojure.edn/read-string args)))
                              [query db])
        results (apply d/q query-with-args)
        limit  (or (string->number limit)  10)
        offset (or (string->number offset) 0)
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
  (let [{:keys  [service dburi]} (:params request)
        kw      (keyword service)
        extant  (kw @registered)
        ensured (and (not extant) (ensure-db dburi))
        new-reg (and (not extant) (swap! registered assoc kw dburi))]
    (if-not extant
      {:data     {:result new-reg}
       :template "templates/dump.html"}
      {:status 409
       :body   "409 Conflict: Service by that name/alias already exists, cannot create another by the same name."})))

(defn delete-service
  "Deletes database in non-production,
  merely unregisters service in production"
  [request]
  (let [{:keys [service]} (:params request)
        dburi   ((keyword service) @registered)
        dev?    (get-config :dev)
        new-reg (swap! registered dissoc (keyword service))]
    (when dev?
      (d/delete-database dburi))
    {:data     {:result new-reg}
     :template "templates/dump.html"}))

(defn api-routes []
  (routes
   (GET     "/api/v1/services/"          {params :params} (inject-services list-services))
   (GET     "/api/v1/services/:service/" {params :params} (wrap-service query-services))
   (POST    "/api/v1/services/:service/" {params :params} create-service)
   (DELETE  "/api/v1/services/:service/" {params :params} (wrap-service delete-service))

   (GET  "/api/v1/:service/" {params :params} (wrap-service query))
   (POST "/api/v1/:service/" {params :params} (wrap-service transactor))))
