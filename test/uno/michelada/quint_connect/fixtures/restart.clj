(ns uno.michelada.quint-connect.fixtures.restart
  "One init and one halt, and nothing else. Scanned alone it is a legitimate
  driver; scanned alongside `bank` or `forms` it is the cross-namespace
  collision, which is the one a `:scan` list grows into by accident.")

(def restarts (atom 0))

(defn start! {:quint/init true} [] (swap! restarts inc))

(defn stop! {:quint/halt true} [] (reset! restarts 0))
