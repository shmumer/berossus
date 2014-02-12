(ns berossus.api-test
    (:require [clojure.test :refer :all]
              [ring.mock.request :refer [request]]
              [datomic.api :as d]
              [berossus.rocks.your.data.api :refer :all]
              [berossus.rocks.your.data.db :refer [ensure-db gen-schema tx->tempids]]
              [berossus.rocks.your.data.server :refer [app]]
              [berossus.rocks.your.data.services :refer [registered reset-services]]
              [berossus.rocks.your.data.config :refer [get-config]]))

(defn test-conn []
  (let [dburi (:default (get-config :services))]
    (ensure-db dburi)
    (d/connect dburi)))

(def accept-k [:headers "accept"])
(def client-k [:headers "client-id"])
(def token-k  [:headers "token"])

(defn id-accept [r] (assoc-in r accept-k "application/identity"))
(defn authed-request [r]
  (let [with-client-id (assoc-in r client-k "admin")]
    (assoc-in with-client-id token-k "booya")))

(defn prep-req [r] (authed-request (id-accept r)))

(def base-query (prep-req    (request :get "/api/v1/default/")))
(def base-transact (prep-req (request :post "/api/v1/default/")))

(def simple-query '[:find ?e :where [?e :db/ident]])

(def query-request (assoc base-query :params {:query (str simple-query)}))
(def reversed-query-request (assoc-in query-request [:params :post-fn] "(fn [result db] (assert db) (reverse result))"))

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
  (-> (prep-req (request :post (str "/api/v1/services/" service-name "/")))
      (assoc-in [:params :dburi] "datomic:mem://testdb")
      (assoc-in [:params :service] service-name)))

(defn delete-request [service-name]
  (prep-req (request :delete (str "/api/v1/services/" service-name "/"))))

(def junk-query (assoc base-query :params {:query "blahblahblah"}))

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
    (is (= (count (:result (:data (app query-request)))) 10)))
  (testing "Can get sorted results back from the API"
    (init-test-db!)
    (is (= '([25] [26] [27] [21] [22] [23] [24] [17] [18] [20]) (:result (:data (app reversed-query-request)))))))

(deftest berossus-transact-api-test
  (testing "Can transact via the API"
    (init-test-db!)
    (is (map? (app transact-request))))
  (testing "Can get original tempid -> new id mapping"
    (init-test-db!)
    (let [tempify  (assoc-in transact-request [:params :tempify] true)
          gathered (tx->tempids (read-string (get-in tempify [:params :transactee])))
          result   (app tempify)
          tempids  (set (keys (get-in result [:data :result :tempids] result)))]
      (is (every? (partial contains? tempids) gathered))))
  (testing "Can get attribute ident instead of id #"
    (init-test-db!)
    (let [attr-ident (assoc-in transact-request [:params :attr-ident] true)
          result     (app attr-ident)
          tx-data    (:tx-data (:result (:data result)))]
      (is (every? #(contains? % :a) tx-data))
      (is (every? #(not (integer? (:a %))) tx-data))
      (is (= (vec (sort (mapv :a tx-data))) [:db/txInstant
                                             :message/timestamp
                                             :message/uuid])))))


(deftest berossus-pagination-api-test
  (testing "Can paginate via the API"
    (init-test-db!)
    (is (= (count (:result (:data (app (paginate-request 27 0))))) 27))
    (is (= (count (:result (:data (app (paginate-request 51 5))))) 46))
    (is (= (count (:result (:data (app (paginate-request "27" "0"))))) 27))
    (is (= (count (:result (:data (app (paginate-request "51" "5"))))) 46))))

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
    (let [resp      (app (create-request "test-service"))
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

(def touch-query-rules
  '[[[(touch ?eid ?touched)
    [(datomic.api/entity $ ?eid) ?ent]
    [(datomic.api/touch ?ent) ?touched]]]])
(def touch-query '[:find ?touched :in $ % :where [?e :db/doc ?doc]
                  (touch ?e ?touched)])

(def param-query
  (update-in base-query [:params] assoc :query (str touch-query) :args (str touch-query-rules)))

(def unique-keys (comp distinct (partial mapcat keys)))

(deftest berossus-query-params-test
  (testing "Can add params to database query"
    (init-test-db!)
    (let [resp (app param-query)
          result (:result (:data resp))]
      (is (= (count result) 10))
      (is (= (unique-keys (mapcat concat result))
             '(:db/doc :db/ident :db/cardinality :db/valueType :db.install/function
               :db.install/attribute :db.install/valueType :db.install/partition :fressian/tag))))))

(defn hush-now [fn & args]
  (with-redefs [*out* (new java.io.StringWriter)
                *err* (new java.io.StringWriter)]
    (apply fn args)))

(deftest berossus-api-bad-inputs
  (testing "Bad query returns a proper error"
    (init-test-db!)
    (let [resp (hush-now app junk-query)]
      (is (= (:status resp) 500))
      (is (= (.contains (:body resp) "query must be a readable edn string"))))))

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
