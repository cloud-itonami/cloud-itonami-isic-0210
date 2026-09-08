(ns forestry.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave1 Lane A-hand): drives the REAL actor stack
  (`forestry.operation` -> `forestry.governor` -> `forestry.store`)
  through a scenario adapted from this repo's own `forestry.sim` demo
  driver (`clojure -M:dev:run`, confirmed against the real seeded stand
  ids `stand-001`..`stand-003` which match `forestry.store` sample data),
  trimmed to a representative subset (one clean stand-record auto-commit,
  a thinning schedule + forest-health concern both human-approved, and
  two distinct HARD-hold reasons) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verify by diffing two consecutive
  runs). Skin is `jp-go-dds.skin/dds+skin` (REF cloud-itonami-isic-9522).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [forestry.store :as store]
            [forestry.operation :as op]
            [langgraph.graph :as g]))

(def ^:private coordinator
  {:actor-id "coord-1" :actor-role :forestry-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context coordinator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded stand directory through a scenario mixing every
  disposition this actor can reach: stand-001 clears a clean stand-record
  (phase-3 auto-commit when propose-shaped + verified), a thinning field-
  operation schedule (ALWAYS escalates -- approved) and a forest-health
  concern (ALWAYS escalates -- approved); stand-002 HARD-holds a mis-wired
  request whose own `:effect` is `:direct-write` rather than `:propose`
  (`not-propose-effect`, never reaches a human); stand-003 HARD-holds a
  stand-record patch that declares a fabricated `:health-status` outside
  the closed known set (`invalid-health-status`, never reaches a human).
  Every id/op/value is from forestry.sim / governor / store -- every field
  read by `render` below is real governor/store output, not a hand-typed
  copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    ;; clean stand-record on verified stand-001 -> phase-3 auto-commit
    (exec! actor "t1" {:op :log-stand-record :effect :propose :subject "stand-001"
                       :patch {:health-status :healthy :last-assessed "2026-07-14"}})

    ;; thinning schedule on verified stand-001 -> escalate, human approve
    (exec! actor "t2" {:op :schedule-field-operation :effect :propose :subject "op-1"
                       :value {:stand-id "stand-001" :operation-type :thinning
                               :scheduled-date "2026-08-01" :finalize? false}})
    (approve! actor "t2")

    ;; forest-health concern -> always escalate, human approve
    (exec! actor "t3" {:op :flag-forest-health-concern :effect :propose :subject "concern-1"
                       :value {:stand-id "stand-001" :severity :moderate
                               :description "bark-beetle-suspected"}})
    (approve! actor "t3")

    ;; HARD: mis-wired effect (not :propose) on stand-002
    (exec! actor "t4" {:op :log-stand-record :effect :direct-write :subject "stand-002"
                       :patch {:health-status :healthy}})

    ;; HARD: fabricated health-status outside closed set on stand-003
    (exec! actor "t5" {:op :log-stand-record :effect :propose :subject "stand-003"
                       :patch {:health-status :thriving-fabricated
                               :last-assessed "2026-07-14"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger sid]
  (last (filter #(= (:subject %) sid) ledger)))

(defn- status-cell [ledger sid]
  (let [f (last-fact-for ledger sid)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (or (-> f :violations first :rule)
                     (-> f :basis first))]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- stand-row [ledger {:keys [id site species area-ha health-status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc site) (esc (str species)) (esc (str (or area-ha "—")))
          (esc (name (or health-status :n-a))) (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README / forestry.governor / forestry.phase) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-stand-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean + propose-shaped; HARD on mis-wired effect / invalid health-status</span></td></tr>"
   "        <tr><td><code>:schedule-field-operation</code></td><td><span class=\"warn\">ALWAYS human approval; harvest-finalize permanently blocked; HARD on unverified / immature stand</span></td></tr>"
   "        <tr><td><code>:flag-forest-health-concern</code></td><td><span class=\"warn\">ALWAYS human approval (forest safety)</span></td></tr>"
   "        <tr><td><code>:order-supplies</code></td><td><span class=\"warn\">human approval over cost threshold; HARD on claimed-total mismatch (independent recompute)</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        stands (->> (store/all-stands db) (sort-by :id))
        stand-rows (str/join "\n" (map (partial stand-row ledger) stands))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0210 &middot; forestry coordination</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Forestry coordination (ISIC 0210) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · field-op / health / supply actuation always human-approved · harvest-finalize permanently blocked</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Logging stands</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>forestry.store</code> via <code>forestry.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly. No invented data.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Stand</th><th>Site</th><th>Species</th><th>Area (ha)</th><th>Health</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     stand-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Forestry Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Stands must be verified before any field operation; harvest-finalize is permanently out of scope. Health status must be one of the closed known set; a mis-wired non-propose effect is blocked outright.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        out-file (java.io.File. out)]
    (.. out-file getParentFile mkdirs)
    (spit out-file (render db))
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts )")))
