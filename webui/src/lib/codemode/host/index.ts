/**
 * The code-mode host's proposal lifecycle (codemode.md sections 5.5 and 5.6, issue 346): the proposal store, the pure
 * classification and grant rules, and the transport over the two existing ops. Outside the sandbox; the SDK and the chat
 * tool reach only `store.agent`.
 */
export { classify, type Blocker, type Classification, type ClassifyInput, type Effect, type OperationClass, type ReportDocument } from './classify';
export { decide, gestureFromEvent, GestureError, GrantSet, type ApprovalGesture, type Decision, type GrantView } from './grants';
export { isObjectId, objectId, type ObjectId } from './objects';
export {
  createProposalStore,
  ProposalStore,
  type AgentPort,
  type ApproveResult,
  type ApproveTarget,
  type ChangeLogEntry,
  type Plan,
  type Problem,
  type ProblemPhase,
  type Proposal,
  type ProposalInput,
  type ProposalState,
  type ProposeOptions,
  type Snapshot,
  type StoreOptions,
} from './store';
export {
  CODEMODE_CAPABILITY,
  createRpcTransport,
  MalformedReport,
  parseReport,
  type ReportDiagnostic,
  type TransactionCall,
  type TransactionReport,
  type TransactionTransport,
} from './transport';
