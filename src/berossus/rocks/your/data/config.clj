(ns berossus.rocks.your.data.config
  (:require [environ.core :refer [env]]))

(defn dev? []
  (not (env :production nil)))

(defn parse-services [services]
  (read-string services))

(defn get-config
  ([]
     {:dev      (dev?)
      :services (merge (parse-services
                        ;; {:my-database "fuckyeahdatabase_URL"}
                        (env :berossus-services "{}"))
                       {:default "datomic:mem://dev"})})
  ([key]
     (get-config key nil))
  ([key fallback]
     ((get-config) key fallback)))
