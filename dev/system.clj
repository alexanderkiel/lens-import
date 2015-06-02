(ns system
  (:use plumbing.core))

(defn env [] {})

(defn system [env]
  {})

#_(defnk start [:as system]
  (let [stop-fn (run-server (app more) {:port port})]
    (assoc system :stop-fn stop-fn)))

#_(defn stop [{:keys [stop-fn] :as system}]
  (stop-fn)
  (dissoc system :stop-fn))

