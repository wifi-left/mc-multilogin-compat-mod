# mc-multilogin-compat-mod

A Fabric server-side mod for Minecraft **1.21.1** (Mojang mappings) that integrates with
[MC-MultiLogin-service](https://github.com/wifi-left/MC-MultiLogin-service).

We suggest you to use this with [Authlib-Injector](https://github.com/yushijinhun/authlib-injector/). This mod only change the login method and the login-failed message.

## Features

- On every player login the mod calls the configured MC-MultiLogin-service endpoint with
  `detail=true`, so authentication failures return a structured JSON error instead of the
  generic "Failed to verify username!" message.
- Detailed error reasons (`DUPLICATE_NAME`, `BANNED`, `NOT_FOUND`, …) are forwarded directly
  to the connecting client in the disconnect screen.
- **Auto-rename on `DUPLICATE_NAME`** (configurable): when the service reports that a player
  name is already taken by another skin-site account, the mod automatically retries the login
  with the service-suggested `availableId` (e.g. `Steve_2`) and records the
  original → new-name mapping in `config/mc-multilogin-renames.json`.

## Setup

1. Place the mod JAR in your server's `mods/` directory.
2. Start the server once – a default config file will be generated at
   `config/mc-multilogin-compat.json` and the server will **stop with an error** asking you
   to fill in the API URL.
3. Edit the config and set `apiUrl` to the base URL of your MC-MultiLogin-service method
   entry (e.g. `http://127.0.0.1:25600/login/my`).
4. Restart the server.

## Configuration (`config/mc-multilogin-compat.json`)

```json
{
  "apiUrl": "http://127.0.0.1:25600/login/my",
  "autoRename": true
}
```

| Field        | Type    | Default | Description |
|-------------|---------|---------|-------------|
| `apiUrl`    | string  | `""`    | **Required.** Base URL of the MC-MultiLogin-service login method (e.g. the path configured under `method[].url`). The mod appends `/session/minecraft/hasJoined?…` automatically. |
| `autoRename`| boolean | `true`  | When `true`, a `DUPLICATE_NAME` rejection triggers an automatic retry using the `availableId` returned by the service. The rename is persisted in `config/mc-multilogin-renames.json`. Set to `false` to disable and show the error instead. |

## Building

```bash
# Requires Java 21 and internet access to maven.fabricmc.net
./gradlew build
```

The output JAR will be in `build/libs/`.
