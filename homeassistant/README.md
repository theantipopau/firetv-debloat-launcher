# Home Assistant files

- `tvhome.yaml`: HA package (shell commands, scripts `tv_alert` and `tv_sleep_timer`, automations for the info panel, keep-watching refresh, nightly reboot, example doorbell).
- `tvhome_keep.py`: builds the Plex "keep watching" cards; runs inside HA via `shell_command.tvhome_keep`.

Setup steps: [../docs/HOME-ASSISTANT.md](../docs/HOME-ASSISTANT.md). Prompts for an AI agent: [../docs/AI-PROMPTS.md](../docs/AI-PROMPTS.md).
