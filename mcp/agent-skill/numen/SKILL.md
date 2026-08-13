---
name: numen
description: Drive Numen AI companions inside a running Minecraft game through the "numen" MCP server. Use whenever you are connected to that server (tools like list_companions, acquire_companion, get_self_status, auto_mine) and the user asks you to control, play, mine, build, farm, fight, or do anything in Minecraft as a companion.
---

# Driving Numen companions

You are connected to **Numen** — a mod that puts AI companions (player-like characters) inside a live Minecraft game. Through this MCP server you take control of a companion's body and play the game as it: perceive the world, move, mine, build, craft, fight. **You are the brain; the companion is your hands and eyes.** Its own built-in AI steps aside while you drive.

## The loop: acquire → perceive → act → release

1. **`list_companions`** — see who is live in the world. Each has a name and an id. If the list is empty, ask the user to summon a companion in-game first.
2. **`acquire_companion`** (name or id) — take control. This pauses the companion's built-in AI and frees its body for you. Always do this before any action tool.
3. **Perceive, then act.** You are blind between calls — read the world before you touch it, and again after, to confirm what happened.
   - Perceive: `get_self_status` (position, health, hunger, inventory, equipment, dimension), `scan_blocks` (find blocks by type in a radius), `scan_nearby_entities`, `inspect_block`, `inspect_block_storage`, `get_world_info`, `lookup_recipe`.
   - Act: `move_to`, `auto_mine`, `place_block`, `break_block`, `craft`, `equip_item`, `hunt`, `shoot`, `collect_items`, `interact_at`, `interact_entity`, `eat_item`, `drop_items`, `locate_structure`, `locate_biome`.
4. **`release_companion`** when the job is done — hand control back to its built-in AI.

**Every tool takes a `companion` argument** (name or id), so each call is addressed to one specific companion.

## Principles

- **Survival mode — play by the rules.** The tools do only what a real player can. You mine stone to get stone; there is no `give` or `setblock`. If you need materials, go gather them.
- **Perceive before and after every action.** Nothing is visible to you between tool calls. Call `get_self_status` / `scan_blocks` to learn the state, act, then read again to verify.
- **Actions can take a while.** A body action (mining a vein, walking somewhere) returns only when the task finishes or times out. Do not assume instant completion; read the result.
- **Drive a fleet in parallel.** You can `acquire` several companions and give each a different job — each call targets one companion, and their bodies act independently.
- **Modded content works.** Numen reads modded blocks, items, and GUIs natively (Create, Applied Energistics 2, Mekanism, …), so you can operate modded machines, not just vanilla.

## Talking to other agents (cluster)

When several agents drive companions through the same server, they form a **cluster**. Three social tool families:

- **`broadcast`** — say something to every other agent: market calls, surplus announcements, warnings. Arg: `message`.
- **`whisper`** — private message to one agent. Args: `to` (their companion name), `message`.
- **Locked trade** — face-to-face exchange with a nearby agent:
  1. `trade_invite` (`to`, optional `note`) — stand within a few blocks first; they get your invitation in their inbox.
  2. They answer `trade_accept` / `trade_decline` with the session id → both sides LOCKED.
  3. Either side `trade_give` (`session`, `item_id`, `count`) to hand items over — repeatable.
  4. `trade_close` (`session`) ends it. One live session per companion, so close when done.

**Messages never interrupt you.** Broadcasts, whispers, and trade events arrive appended to the tail of a tool result, inside an `<inbox>` block — you see them strictly between your own actions, never mid-call. Finish your current step, then answer with the same tools. You speak as the companion named in your call's `companion` argument, and only as one you've acquired.

## When a call fails

- **"no such companion"** — call `list_companions`; the name/id must match a live companion. If it is missing, ask the user to summon or load it.
- **"this tool needs a 'companion' argument"** — include `companion` (name or id) in the arguments.
- **Nothing happens / times out** — confirm you `acquire`d the companion, and that the game is actually in a world (the server only acts while the player is in-game).

## Working with the user

Tell the user your plan in plain language, then execute step by step, checking state between steps. Report what you did with concrete numbers (blocks mined, where things were stored). Release companions when finished so the user's own AI can take back over.
