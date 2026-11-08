# Deployment (Render + GitHub)

The backend deploys to [Render](https://render.com) as a free HTTPS web service. `render.yaml` at the repo root defines it as a Blueprint. The Android **release** build calls that URL; the **debug** build calls a local backend.

## 1. Push the repository to GitHub (public, MIT)

```bash
git remote add origin https://github.com/<you>/CartPilot.git
git push -u origin main
```

`backend/.env` is gitignored, and the committed `backend/.env.example` contains placeholders only. CI (`.github/workflows/ci.yml`) runs the backend tests and the Android build, unit tests and lint on every push.

## 2. Create the Render service

1. Render Dashboard → **New → Blueprint** → connect GitHub → pick the CartPilot repo.
2. Render reads `render.yaml` and creates the web service **cartpilot-api**:
   - root directory `backend`
   - build command `pip install -r requirements.txt`
   - start command `uvicorn app.main:app --host 0.0.0.0 --port $PORT --proxy-headers`
   - health check `/api/v1/health`, Python 3.12.10
3. It asks for the secret values (`sync: false`):

   | Key | Value |
   |---|---|
   | `GEMINI_API_KEY` | your Gemini key |
   | `PAYPAL_CLIENT_ID` | PayPal **Sandbox** REST app client ID |
   | `PAYPAL_CLIENT_SECRET` | PayPal **Sandbox** REST app secret |

   The non-secret values are already set in the Blueprint: `GEMINI_MODEL`, `GEMINI_FALLBACK_MODELS`, `PAYPAL_BASE_URL`, `ENVIRONMENT`, `FORWARDED_ALLOW_IPS`.
4. Deploy. When it's live:
   - `https://<service>.onrender.com/api/v1/health` shows `"gemini_configured": true, "paypal_configured": true`
   - `https://<service>.onrender.com/docs` shows the interactive API docs

PayPal return/cancel URLs use Render's `RENDER_EXTERNAL_URL` automatically, so you don't need to set `PUBLIC_BASE_URL`. `--proxy-headers` together with `FORWARDED_ALLOW_IPS=*` makes the backend trust Render's `X-Forwarded-Proto`, so product image URLs come out as `https://`.

## 3. Point the Android release build at it

If Render gave the service a different URL (for example, the name `cartpilot-api` was taken), update `cartpilot.releaseApiBaseUrl` in `android/gradle.properties`, or override it in `android/local.properties`. Then:

```bash
cd android
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (signed with the debug key for the demo)
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Things to know about the free plan

- **Cold starts:** a free service sleeps after about 15 minutes idle, and the first request then takes 30–60 s. Open `/api/v1/health` a minute before demoing. The app's 120 s read timeout covers a cold start.
- **In-memory state:** carts, chat history and pending payments live in memory and are cleared on every deploy or restart. That's fine for the demo, but don't redeploy in the middle of a checkout.
- **Single instance:** the in-memory design assumes one instance, so don't scale it out.
- **Gemini quota:** the free tier allows about 20 requests per day per model. Enable billing on the Google AI project before recording the demo.
