/**
 * HealthStatus — the pure domain model of GET /api/health, the liveness probe
 * behind the health view's live status readout. `status` stays a plain string
 * (Spring's actuator-style "UP"/"DOWN") until the server contract settles.
 */
export interface HealthStatus {
  status: string;
}
