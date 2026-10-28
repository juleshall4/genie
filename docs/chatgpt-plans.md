# ChatGPT plans in Genie

Status checked October 28, 2026.

## Sign-in and plan eligibility

Sign in with ChatGPT has two separate permissions: account identity and optional ChatGPT plan usage. OpenAI currently offers plan usage to open-source apps and personal projects that run locally. Its [Sign in with ChatGPT quickstart](https://developers.openai.com/siwc/quickstart) and [open-source app guide](https://learn.chatgpt.com/cookbook/articles/sign-in-with-chatgpt) name eligible ChatGPT Plus and Pro users for plan-powered requests.

OpenAI does not document ChatGPT Free as eligible for direct Sign in with ChatGPT plan usage. Treat Free eligibility as unconfirmed. The separate inclusion of Codex in the [Free plan](https://learn.chatgpt.com/docs/pricing) does not establish that a Free account can use its plan through a third-party SIWC client; these are different product paths. Identity sign-in and plan usage are separate, so do not promise inference based on a successful sign-in alone.

If a request returns `subscription_sharing_user_not_eligible` (403), explain that the selected account, workspace, or policy is not eligible. Do not repeat the same request or send the user through OAuth again. OpenAI documents this recovery in [Errors and recovery](https://developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery).

## Reasoning effort

The Responses API supports model-dependent `reasoning.effort` values: `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, and `max`. Lower effort favors speed and lower token use; higher effort can improve complex work at the cost of time and usage. Check each model's advertised support; do not assume every model accepts every value. See OpenAI's [reasoning guide](https://developers.openai.com/api/docs/guides/reasoning).

| Effort | Use |
| --- | --- |
| `none`, `minimal` | Fast tasks, only when the selected model supports them. |
| `low` | Routine tasks where speed and usage matter. |
| `medium` | A balanced default for planning and judgment. |
| `high`, `xhigh` | Hard debugging, research, and multi-step work; use higher settings when the quality gain is worth the added usage and latency. |
| `max` | The hardest quality-first tasks. |

In the authorized SIWC `/v1/models` catalog checked for Genie, the first visible model was `gpt-6.1-sol`, with default `low` and advertised levels `low`, `medium`, `high`, `xhigh`, `max`, and `ultra`. The public Responses guide lists `medium` as GPT-6.1 Sol's default and lists levels only through `max`. Use the SIWC catalog's default for that account, and treat `ultra` as unconfirmed until a successful Responses request verifies it. Genie now offers model and reasoning pickers in settings; automatic selection uses the catalog's first visible model and its advertised default. Treat model availability and defaults as account- and workspace-specific.

## Usage limits and controls

Requests use the user's existing ChatGPT plan usage or credits; connecting Genie does not add an allowance. OpenAI says Plus users share a five-hour usage limit across ChatGPT plan apps, including open-source clients. Pro users do not have that five-hour limit. App-specific limits can also apply. See [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions).

Keep a **Manage usage** action in Genie and open ChatGPT Settings → Usage. Show when a request uses the ChatGPT plan, and direct users there when a limit is reached. OpenAI's [UI/UX guidelines](https://developers.openai.com/siwc/ui-ux-guidelines) recommend showing this status near the composer or model selector and making **Manage usage** the primary limit action.

In **Settings > Usage > App limits**, users can set Genie's weekly limit as a percentage of their overall weekly usage. The limit caps Genie's consumption; it does not reserve an allowance or add usage to the plan. See [Manage app usage](https://learn.chatgpt.com/docs/sign-in-with-chatgpt#see-and-manage-usage).

For `subscription_sharing_usage_limit_exceeded` (429), pause plan-powered requests and direct the user to usage settings. Do not assume the whole plan is depleted or promise a reset time; an app-specific limit may be responsible.
