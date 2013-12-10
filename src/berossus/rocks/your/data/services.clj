(ns berossus.rocks.your.data.services
  (:require [berossus.rocks.your.data.config :refer [get-config]]))

(defn gen-services []
  (get-config :services))

(defonce registered (atom (gen-services)))

(defn reset-services []
  (reset! registered (gen-services)))
