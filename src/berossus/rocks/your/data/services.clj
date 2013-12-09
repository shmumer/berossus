(ns berossus.rocks.your.data.services
  (:require [berossus.rocks.your.data.config :refer [get-config]]))

(defonce registered (atom (get-config :services)))
