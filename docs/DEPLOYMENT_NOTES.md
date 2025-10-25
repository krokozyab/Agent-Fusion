# Deployment Notes

## Browser Auto-Launch

- Configuration key: `web.autoLaunchBrowser`
- Default: `true`
- Behaviour: When the web dashboard server starts, the launcher waits 500 ms for readiness and then opens the dashboard URL (defaults to `http://localhost:8081`, or `https://` when SSL is enabled).
- Disable: Set `web.autoLaunchBrowser = false` in `application.conf`, or provide an environment-expanded value:
  ```hocon
  web {
    autoLaunchBrowser = ${?WEB_AUTO_LAUNCH_BROWSER}
  }
  ```
  Set `WEB_AUTO_LAUNCH_BROWSER=false` to keep the browser from opening automatically (recommended for headless deployments and remote servers).
- Headless environments: The launcher falls back to `cmd /c start`, `open`, or `xdg-open`. Failures are logged at WARN level without interrupting startup.

Keep this flag enabled for local development to reduce setup friction, and disable it for CI/CD or production deployments where no desktop session is available.
