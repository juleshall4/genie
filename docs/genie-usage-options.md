# Preserve ChatGPT usage for Genie

Status checked October 28, 2026.

## How shared usage works

Genie’s Sign in with ChatGPT requests use the user’s existing ChatGPT plan usage. They do not add a separate allowance. Codex and ChatGPT Work share usage, and message cost varies with the model, task, reasoning, tools, and context. Plus has five-hour and weekly limits; local and cloud usage share those limits. [OpenAI’s pricing guide](https://learn.chatgpt.com/docs/pricing) says an active Codex turn can continue after a limit is reached, so a preflight check cannot stop all usage at an exact percentage.

## Available controls

| Option | What it can do | Limit |
| --- | --- | --- |
| ChatGPT app limit for Genie | Set a weekly percentage cap for Genie in ChatGPT Settings → Usage. | The cap limits Genie’s use; it does not reserve that portion for Genie. Codex may still consume the shared plan usage. [OpenAI’s SIWC guide](https://learn.chatgpt.com/docs/sign-in-with-chatgpt) states that an app limit is a cap, not a reserved balance. |
| Check usage in Codex | Show remaining limits with `/status` in a Codex CLI session. | This reports usage; it does not throttle work. The documented personal-plan controls do not describe a setting that caps first-party Codex at 98% or reserves the final 2% for Genie. |
| Gate work through a custom Codex wrapper | Use app-server’s `account/rateLimits/read` and `account/rateLimits/updated` to inspect usage windows, then stop launching new Codex tasks before the chosen threshold. | This only gates work started through the wrapper. It cannot control stock Codex desktop, IDE, CLI, cloud tasks, other clients, or other devices. Usage can change between checks, and an active Codex turn can continue after a limit is reached. OpenAI marks app-server experimental and unsupported for production workloads. See the [app-server guide](https://learn.chatgpt.com/docs/app-server). |
| Optional API-funded Genie | Let the user explicitly configure an API key and route Genie requests to a separate API project. | This requires API billing and a key, so it changes the no-key SIWC setup. API usage is separate from ChatGPT plan usage. A project hard spend limit can return `429` responses when tracked monthly spend reaches the cap. See [API pricing and limits](https://developers.openai.com/api/docs/guides/rate-limits). |

## Recommendation

There is no documented way to guarantee that first-party Codex stops at 98% and leaves 2% of a personal ChatGPT plan for Genie. For a no-key setup, the closest option is a best-effort wrapper for Codex sessions that the user starts through it. Have it read every returned usage bucket, stop starting new work below the target threshold, and show the current remaining usage in Genie. Leave a buffer above 2% because task usage varies and active work can overshoot the threshold. This will not protect Genie from Codex activity outside the wrapper.

If the user needs an independent, enforceable Genie budget, offer API billing only as an explicit opt-in. Put Genie on its own API project with a hard monthly spend limit. This separates Genie’s budget from ChatGPT subscription usage, but it requires paid API setup and does not preserve a percentage of the ChatGPT plan.

API spend-limit enforcement can lag: recorded spend may slightly exceed the configured amount while the limit state propagates. A separate API budget protects Genie from exhaustion of the ChatGPT subscription allowance; it does not guarantee service availability or an exact maximum charge. See [Hard-limit behavior](https://developers.openai.com/api/docs/guides/spend-limits#understand-hard-limit-behavior).
