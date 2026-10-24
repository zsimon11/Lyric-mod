# Lyricscord (Minecraft 26.2, Fabric)

Shows your Spotify "now playing" bar and live synced lyrics at the top of your screen.

Needs: Minecraft 26.2, Fabric Loader 0.19.3+, Fabric API 0.154.2+26.2, Java 25.

## Build it on GitHub (no installs needed)
1. Create a new GitHub repository (any name, Public or Private).
2. Upload everything in this folder, including the hidden `.github` folder.
   If `.github/workflows/build.yml` is missing after upload: Add file > Create new file,
   type `.github/workflows/build.yml` as the name and paste the contents from this project.
3. Open the repo's **Actions** tab. The "Build mod" run starts by itself.
4. When it shows a green check, open the run and download **lyricscord-jar** under Artifacts.
5. Unzip it. Put the `.jar` in `.minecraft/mods` together with Fabric API.

## Spotify setup (one time)
1. Go to https://developer.spotify.com/dashboard and create an app.
2. Add this Redirect URI exactly: `http://127.0.0.1:8898/callback`
3. Tick "Web API", save, and copy the **Client ID** from the app settings.
4. In Minecraft chat:
   - `/lyricscord setup <your client id>`
   - `/lyricscord login` (approve in the browser tab that opens)

## Commands
`/lyricscord setup <clientId>`, `login`, `logout`, `toggle`, `lyrics`, `offset <ms>`, `status`
