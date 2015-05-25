(ns lens.core
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as str]))

(def cli-options
  [["-w" "--warehouse-uri URI" "The Lens Warehouse URI to use"
    :validate [#(.startsWith % "http")
               "Database URI has to start with http."]]
   ["-h" "--help" "Show this help"]])

(defn usage [options-summary]
  (->> ["Usage: lens-import [options]"
        ""
        "Options:"
        options-summary
        ""]
       (str/join "\n")))

(defn error-msg [errors]
  (str/join "\n" errors))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main [& args]
  (let [{:keys [options errors summary]} (cli/parse-opts args cli-options)
        version (System/getProperty "lens-import.version")]
    (cond
      (:help options)
      (exit 0 (usage summary))

      errors
      (exit 1 (error-msg errors))

      (nil? (:warehouse-uri options))
      (exit 1 "Missing Lens Warehouse URI."))

    (let [{:keys [warehouse-uri]} options]
      (println "Version:" version)
      (println "Max Memory:" (quot (.maxMemory (Runtime/getRuntime))
                                   (* 1024 1024)) "MB")
      (println "Num CPUs:" (.availableProcessors (Runtime/getRuntime)))
      (println "Datomic:" warehouse-uri)
      )))
