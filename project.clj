(defproject Mardika "0.0.1"
  :description "An open source Clojure streaming site"
  :dependencies [[http-kit "2.6.0"] [org.clojure/clojure "1.10.3"] [ring/ring-core "1.13.0"] [ring/ring-defaults "0.3.4"] [compojure "1.6.2"]]
  :source-paths ["src/main/backend"]
  :main server)