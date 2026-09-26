# Driving playbook

Detail for `ad-drive`. The interaction rules in "Acting" adapt guidance from Google's `android-cli` agent skill (github.com/android/skills, `devtools/android-cli`, Apache-2.0) to this plugin's daemon-backed tools.

## Event kinds (Monitor lines and `ui_wait` results)

| kind | Meaning |
|---|---|
| `screen_changed` | The settled screen's visible text changed. `added` / `removed` lists; `first: true` is the baseline. `digits_only` = only numbers changed (a quantity, a total, a clock). |
| `ui_window` | A window event: a dialog/popup opened (`DialogWrapper`), an activity came to front, a toast, an announcement. |
| `ui_action` | One of our own inputs (tap, key, type, swipe) — so the timeline shows cause next to effect. |
| `screen_unreadable` | The screen could not be read (`timeout`: UI thread frozen; `no_root`: secure window or transition). Reported once per code. |
| `ui_disconnected` | The daemon or adb connection died. Call `ui_start` again. |
| `ui_stopped` | `ui_stop` ran. The follower exits. |

Countdowns are collapsed: the first tick is reported, the rest are counted into `suppressed_ticks_before` on the next change. Your own inputs are never collapsed.

## Acting

- **Find, then act.** Read `ui_layout` before acting on a new screen. Tap by `text` / `content_desc` / `resource_id`; coordinates only when the element has no label.
- **Icon-only buttons** (a "+" drawn as an icon with no content-desc) have no text in the tree. Tap by the `center` of the right node, and tell the user the button is unlabeled — it is also an accessibility bug in their app.
- **Typing:** the field must be focused (`"focused"` in its `state`). `ui_type` with a selector taps it first. `ui_type` replaces the field's content; no escaping needed.
- **Scrolling:** swipe opposite to the scroll direction (swipe up to scroll down), `duration_ms` ≥ 400. Re-read after each swipe; stop when nothing new appears.
- **Slow content:** if an expected element is missing after an action, `ui_wait(until_text: ...)` instead of re-reading in a loop.
- **WebViews, canvases, images, animations** may not appear in the layout: `ui_screenshot`, then `Read` the PNG to see it.
- **Keys:** `ui_key` with `back`, `home`, `enter`, ... Kiosk apps often ignore `home`.

## Errors

| code | What to do |
|---|---|
| `ui_not_started` | `ui_start` (the daemon may have died — check `ui_status`). |
| `ui_element_not_found` | Re-read `ui_layout`; try `contains: true`, another selector, or coordinates. |
| `ui_daemon_error` + `daemon_code: timeout` | UI thread didn't answer. Paused in the debugger? Otherwise the app is janky — retry once. |
| `ui_daemon_error` + `daemon_code: no_root` | Transition or secure window. `ui_wait`, then retry. |
| `ui_daemon_error` + `daemon_code: no_focus` | Tap the field first. |
| `vm_paused` | The attached app is suspended in the debugger. Resume first. |
| `tool_timeout` | The device stalled. `ui_status`; restart the session if `running` is false. |

## Presence (Compose apps)

Compose deliberately sends no accessibility events while no accessibility service is enabled (it treats that state as "UIAutomator is running"). `presence: true` installs a service that observes nothing, only so Compose emits. It is enabled before the daemon connects, appended to any services the user already has (TalkBack keeps working), and on `ui_stop` the exact prior values are restored and the app uninstalled. If the server is killed mid-session, the next `ui_start` repairs the settings from an on-device marker. Apps that change behavior when "accessibility is on" may act differently while it runs — mention that when asking.
