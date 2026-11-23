# DSH-GPT UI architecture (locked)

## Product shell

- Native Android application, Kotlin, Jetpack Compose, Material 3.
- Six persistent bottom-level destinations: Overview, API, Conversations, Hook, Agent, GPT UI Customizer.
- Settings remain a global top-bar destination, not a seventh bottom tab.
- Light/dark follows the system by default. No screenshot or image-based UI.
- Pages use real app/runtime data. Unknown or unavailable values must be shown as unavailable, never fabricated.

## Navigation and state

- The selected top-level destination survives activity recreation.
- Each destination owns its scroll position and nested navigation state when nested routes are added.
- Background API and Hook services must not be tied to the currently visible destination.
- UI reads and writes through service APIs/repositories; composables must not own networking or long-running work.

## Destinations

### Overview
- API gateway state and credential readiness.
- LSPosed service connection and target package.
- Recent activity and shortcuts are added only when backed by real event sources.

### API
- Existing local relay start/stop and RelayConfig editing.
- Mask API keys and upstream tokens.
- Provider registry, capability negotiation, request history, and conversation reuse are separate follow-up integrations.

### Conversations
- Conversation list, search, detail, continuation, and parent/child agent links.
- Empty state must explain when the persistent session store is not yet connected.

### Hook
- Existing declarative Hook config validation/submission.
- Metadata-only observation report.
- Discovered classes, configured rules, installed hooks, and verified activity are distinct states.

### Agent
- Main conversation selection/creation, child-agent conversation assignment, hierarchy depth, delegation, parallelism, and result aggregation.
- UI controls remain visibly unavailable until backed by the DSH-native orchestration/runtime contract.

### GPT UI Customizer
- Menu inventory, visibility, ordering, custom actions, Hook bindings, compatibility, and rollback.
- Do not claim a menu item is modified until the target-side binding is detected and verified.

## Reusable components

- DshTopBar
- DshBottomNavigation
- DshPageHeading
- DshSectionCard
- DshStatusLine
- DshMetricCard
- DshEmptyState
- DshErrorState
- DshLoadingState
- ConversationCard
- AgentTreeNode
- HookClassCard
- HookMethodRow
- ProviderCard

## Delivery stages

1. Compose shell and six-tab navigation.
2. Connect existing relay and Hook configuration/report flows.
3. Add persistent conversations and provider registry.
4. Integrate DSH-native Agent orchestration (do not duplicate DSH's subagent engine).
5. Add verified GPT UI customization runtime.
6. Device validation, accessibility, process-death restoration, and CI.

## Current implementation boundary

The first UI pass implements the shell and connects only the relay and Hook configuration/report capabilities that already exist in this branch. Conversation persistence, provider registry, Agent orchestration, Hook class inventory, and GPT menu mutation are explicitly marked as not yet integrated.
