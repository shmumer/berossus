(ns berossus.rocks.your.data.config
  (:require [environ.core :refer [env]]))

(defn dev? []
  (not (env :production nil)))

(defn read-services [services]
  (read-string services))

(defn read-clients [clients]
  (read-string clients))

(defn get-config
  ([]
     {:dev      (dev?)
      :clients  (merge {"admin" "booya"}
                       (read-clients (env :berossus-clients "{}")))
      :services (merge {:default "datomic:mem://dev"}
                       (read-services
                        ;; {:my-database "fuckyeahdatabase_URL"}
                        (env :berossus-services "{}")))})
  ([key]
     (get-config key nil))
  ([key fallback]
     ((get-config) key fallback)))
