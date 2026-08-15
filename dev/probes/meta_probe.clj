;; Verifies every metadata mechanic the design in docs/architecture.md relies on.
;; Run: clojure -M dev/probes/meta_probe.clj
;;
;; Note what this namespace does NOT have: a :require of quint-connect. The
;; annotation keys are plain keywords in the `quint` keyword namespace, so an
;; annotated namespace takes on no dependency at all. See
;; docs/decisions/0007-annotation-keys.md.
(ns meta-probe)

;; --- the default vocabulary ------------------------------------------------

(defn deposit {:quint/action "deposit"} [who amount] [who amount])

;; --- attaching -------------------------------------------------------------

;; WORKS: reader metadata on the symbol
(defn ^{:quint/action "settle"} settle [who] [who])

;; WORKS: attr-map after the name (and after the docstring, if any)
(defn withdraw
  "Take money out."
  {:quint/action "withdraw" :quint/args [:who :amount]}
  [account n] [account n])

;; SILENTLY DOES NOTHING: metadata on the (defn ...) form is discarded
^{:quint/action "overdraft"}
(defn overdraft [who amount] [who amount])

;; --- state holders ---------------------------------------------------------

(def ^{:quint/state :balances} accounts (atom {"alice" 0}))
(def ^{:quint/state {:var :lastError :path [:err]}} status (ref {:err ""}))
(def on-the-atom (atom {} :meta {:quint/state :queue}))
(defn current-balances {:quint/state :balances} [] {"alice" 0})
(defn whole-state {:quint/state :*} [] {:balances {} :lastError ""})

;; --- lifecycle -------------------------------------------------------------

(defn start-app {:quint/init true} [] :started)
(defn stop-app  {:quint/halt true} [] :stopped)

;; --- multi-arity and privates ----------------------------------------------

(defn ^{:quint/action "sweep"} sweep
  ([from to] (sweep from to 1))
  ([from to amount] [from to amount]))

(defn- ^{:quint/action "audit"} audit [] :audited)

;; --- a driver-supplied :key-ns ---------------------------------------------

;; What an application writes when `quint` collides and the driver sets
;; {:key-ns 'acme.mbt}. Nothing about the annotation changes but the qualifier.
(defn rebate {:acme.mbt/action "rebate"} [who amount] [who amount])

;; --- what the registry needs to be able to do ------------------------------

;; The registry is parameterised on the keyword namespace, defaulting to
;; "quint". This is the whole of what :key-ns costs the implementation.
(def ^:private default-key-ns "quint")

(defn- var-meta [v]
  (merge (when (instance? clojure.lang.IReference @v) (meta @v))
         (meta v)))

(defn- annotation
  ([v k] (annotation v k default-key-ns))
  ([v k key-ns] (get (var-meta v) (keyword key-ns (name k)))))

(defn- annotated
  ([ns-sym k] (annotated ns-sym k default-key-ns))
  ([ns-sym k key-ns]
   (->> (ns-interns ns-sym)
        (keep (fn [[sym v]] (when-some [a (annotation v k key-ns)] [sym a])))
        (sort-by first)
        vec)))

(defn- picks-from-arglists [v]
  (let [{:keys [arglists]} (meta v)]
    (if (= 1 (count arglists)) (mapv keyword (first arglists)) :ambiguous)))

(println "plain keyword       :" (pr-str :quint/action) "| no require in this ns")
(println "form-meta lost      :" (pr-str (annotation #'overdraft :action)))
(println "symbol meta         :" (annotation #'settle :action))
(println "attr-map meta       :" (annotation #'withdraw :action))
(println "meta on the atom    :" (annotation #'on-the-atom :state))
(println "IReference atom/ref :" (mapv #(instance? clojure.lang.IReference %) [on-the-atom status]))
(println "deref-able kinds    :" (mapv #(instance? clojure.lang.IDeref %)
                                       [(atom 1) (ref 1) (delay 1) (volatile! 1) (agent 1)])
         "| plain fn:" (instance? clojure.lang.IDeref current-balances))
(println "picks from arglists :" (picks-from-arglists #'deposit))
(println "picks, multi-arity  :" (picks-from-arglists #'sweep))
(println "actions found       :" (annotated 'meta-probe :action))
(println "state found         :" (annotated 'meta-probe :state))
(println "init / halt found   :" (annotated 'meta-probe :init) (annotated 'meta-probe :halt))
(println "under :key-ns       :" (annotated 'meta-probe :action "acme.mbt"))
(println "  ... and invisible :" (pr-str (annotation #'rebate :action))
         "<- unread under the default, and nothing reports it")
(println "private var seen    :" (contains? (ns-interns 'meta-probe) 'audit)
         "| via ns-publics:"    (contains? (ns-publics 'meta-probe) 'audit))
(println "apply picks by name :" (let [picks {:amount 50 :who "alice"}]
                                   (apply deposit (map picks (picks-from-arglists #'deposit)))))
