(ns berossus.api-test
    (:require [clojure.test :refer :all]
              [ring.mock.request :refer [request]]
              [datomic.api :as d]
              [berossus.rocks.your.data.api :refer :all]
              [berossus.rocks.your.data.db :refer [ensure-db gen-schema]]
              [berossus.rocks.your.data.server :refer [app]]
              [berossus.rocks.your.data.services :refer [registered reset-services]]
              [berossus.rocks.your.data.config :refer [get-config]]))

(defn test-conn []
  (let [dburi (:default (get-config :services))]
    (ensure-db dburi)
    (d/connect dburi)))

(def accept-k [:headers "accept"])

(defn id-accept [r] (assoc-in r accept-k "application/identity"))

(def base-query (id-accept (request :get "/api/v1/default/")))
(def base-transact (id-accept (request :post "/api/v1/default/")))

(def simple-query '[:find ?e :where [?e :db/ident]])

(def query-request (assoc base-query :params {:query (str simple-query)}))
(def transact-request (assoc base-transact
                        :params
                        {:transactee
                         (pr-str [{:message/uuid #uuid "fdd71557-77c2-43ce-8833-3240f7e03409"
                                   :db/id (d/tempid :db.part/user)
                                   :message/timestamp #inst "2005-01-01"}])}))

(defn paginate-request [limit offset]
  (update-in query-request [:params] merge {:limit limit :offset offset}))

(defn browser-request [request]
  (assoc-in request accept-k "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"))

(defn edn-request [request]
  (assoc-in request accept-k "text/edn"))

(defn create-request [service-name]
  (-> (id-accept (request :post (str "/api/v1/services/" service-name "/")))
      (assoc-in [:params :dburi] "datomic:mem://testdb")
      (assoc-in [:params :service] service-name)))

(defn delete-request [service-name]
  (id-accept (request :delete (str "/api/v1/services/" service-name "/"))))

(defn msg-schema []
  (mapv (partial apply gen-schema)
        [[:message/uuid :db.type/uuid :db.cardinality/one :db/unique :db.unique/identity]
         [:message/timestamp :db.type/instant]]))

(defn test-msgs []
  (mapv #(assoc % :db/id (d/tempid :db.part/user))
        [{:message/uuid #uuid "fdd71557-77c2-43ce-8833-3240f7e03407"
          :message/timestamp #inst "2001"}
         {:message/uuid #uuid "fdd71557-77c2-43ce-8833-3240f7e03408"
          :message/timestamp #inst "2002-02-02"}]))

(defn transact-test-schema! []
  (d/transact (test-conn) (msg-schema)))

(defn transact-test-msgs! []
  (d/transact (test-conn) (test-msgs)))

(defn init-test-db! []
  (d/delete-database (:default (get-config :services)))
  (transact-test-schema!)
  (transact-test-msgs!))

(deftest berossus-query-api-test
  (testing "Can query via the API"
    (init-test-db!)
    (is (= (count (:result (:data (app query-request)))) 10))))

(deftest berossus-transact-api-test
  (testing "Can transact via the API"
    (init-test-db!)
    (is (map? (app transact-request)))))

(deftest berossus-pagination-api-test
  (testing "Can paginate via the API"
    (init-test-db!)
    (is (= (count (:result (:data (app (paginate-request 27 0))))) 27))
    (is (= (count (:result (:data (app (paginate-request 51 5))))) 46))))

(deftest berossus-content-neg-test
  (testing "Can access the API from a browser"
    (init-test-db!)
    (is (= (.contains (:body (app (browser-request query-request)))
                      "<!DOCTYPE html>"))))

  (testing "Can get edn back from the API"
    (init-test-db!)
    (is (= (:body (app (edn-request query-request)))
           "{:result ([37] [38] [39] [40] [35] [36] [45] [46] [47] [48]), :count 51}"))))

(deftest berossus-service-management
  (testing "Can create services at runtime"
    (reset-services)
    (let [resp (app (create-request "test-service"))
          services @registered]
      (is (= services
             (:result (:data resp))
             {:default "datomic:mem://dev"
              :test-service "datomic:mem://testdb"}))))

  (testing "Creating a duplicate service returns HTTP 409"
    (reset-services)
    (let [resp (app (create-request "test-service"))
          duplicate (app (create-request "test-service"))]
      (is (= (:result (:data resp))
             {:default "datomic:mem://dev"
              :test-service "datomic:mem://testdb"}))
      (is (= (:status duplicate) 409))))

  (testing "Can delete services at runtime"
    (reset-services)
    (let [_ (app (create-request "test-service"))
          resp (app (delete-request "test-service"))
          services @registered]
      (is (= services (:result (:data resp)) {:default "datomic:mem://dev"})))))


;; Example of touching inside a query:

;; (defn get-touched []
;;   (d/q '[:find ?touched :in $ %
;;          :where
;;          [?e :db/doc ?doc]
;;          (touch ?e ?touched)]
;;        (d/db (test-conn))
;;        '[[(touch ?eid ?touched)
;;          [(datomic.api/entity $ ?eid) ?ent]
;;          [(datomic.api/touch ?ent) ?touched]]]))
