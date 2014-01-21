(ns berossus.rocks.your.data.api
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [berossus.rocks.your.data.config :refer [get-config]]
            [berossus.rocks.your.data.db :refer [datum->map ensure-db
                                                 id->ident tx->tempids]]
            [berossus.rocks.your.data.middleware
              :refer [inject-req inject-services
                      wrap-export wrap-service]]
            [berossus.rocks.your.data.services :refer [registered]]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]))

;; Move conn and get-db to db.clj
(defn conn [dburi]
  (let [_ (ensure-db dburi)]
    (d/connect dburi)))

(defn get-db
  "Gets a database value and ensures database exists"
  ([db-uri]
     (d/db (conn db-uri))))

(defn get-log
  "Gets a transaction log from the dburi and ensures database exists"
  ([db-uri]
     (d/log (conn db-uri))))

(defn dburi-from-request [request]
  (let [service (:service (:params request))
        dburi   (get-in request [:services (keyword service)])]
    dburi))

(defn tx-attr-id->ident [tx-data db-conn]
  (let [db (d/db db-conn)]
    (mapv (fn [datum]
           (let [mappy (datum->map datum)]
             (update-in mappy [:a]
                        (partial id->ident db))))
           tx-data)))

(defn transactor [request]
  (let [{:keys [transactee tempify attr-ident]} (:params request)
        db-uri     (dburi-from-request request)
        db-conn    (conn db-uri)
        read-txee  (read-string transactee)
        result     @(d/transact db-conn read-txee)
        tempids    (when tempify (tx->tempids read-txee))
        tx-tempids (when tempify (:tempids result))
        resolved   (when tempify
                     (into {}
                           (map (juxt identity
                                #(d/resolve-tempid
                                  (d/db db-conn) tx-tempids %))
                          tempids)))
        result     (or (and tempify
                            (update-in result [:tempids] merge resolved))
                       result)
        result     (or (and attr-ident
                            (update-in result [:tx-data] tx-attr-id->ident db-conn))
                       result)]
    {:data {:result result}}))

(defn string->number [s]
  (if (string? s)
    (and (seq s) (Integer. s))
    s))

(defn query [request]
  (let [{:keys [query limit offset args post-fn]} (:params request)
        query (clojure.edn/read-string query)
        db-uri (dburi-from-request request)
        handle-fn (or (:handle-fn request)
                   get-db)
        handle    (handle-fn db-uri)
        query-with-args (or (and args
                              (concat [query handle]
                                      (clojure.edn/read-string args)))
                              [query handle])
        results (apply d/q query-with-args)
        ;; sigh.
        post-processed (or (and post-fn ((eval (read-string post-fn)) results))
                           results)
        limit  (or (string->number limit)  10)
        offset (or (string->number offset) 0)
        paginated (take limit (drop offset post-processed))
        num-results (count results)]
   {:data
     {:result  paginated
      :count   num-results}
     :template "templates/dump.html"}))

(defn list-services [request]
  (let [services (:services request)]
    {:data     {:result services}
     :template "templates/dump.html"}))

(defn query-services [request]
  (let [service (:service request)]
    {:data     {:result service}
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

   (GET  "/api/v1/:service/"     {params :params} (wrap-service query))
   (GET  "/api/v1/:service/log/" {params :params} (wrap-service
                                                   (inject-req :handle-fn get-log
                                                               query)))
   (POST "/api/v1/:service/"     {params :params} (wrap-service transactor))))
