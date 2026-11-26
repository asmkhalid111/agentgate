import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

const overhead = new Trend('gateway_overhead_ms', true);
const valid = new Rate('valid_governed_calls');
const completed = new Counter('completed_governed_calls');
export const options = {
  scenarios: { measured: { executor: 'constant-arrival-rate', rate: 100, timeUnit: '1s',
    duration: '180s', preAllocatedVUs: 24, maxVUs: 48 } },
  thresholds: { gateway_overhead_ms: ['p(95)<=30'], valid_governed_calls: ['rate==1'],
    completed_governed_calls: ['count==18000'], dropped_iterations: ['count==0'] },
};
function rpc(method, params, session) {
  const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream',
    Authorization: 'Bearer ' + __ENV.AGENTGATE_TEST_TOKEN };
  if (session) headers['Mcp-Session-Id'] = session;
  return http.post((__ENV.QA2_GATEWAY || 'http://localhost:8081') + '/mcp', JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }), { headers });
}
export function setup() {
  const res = rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {},
    clientInfo: { name: 'c5-load', version: '1' } });
  const session = res.headers['Mcp-Session-Id'];
  if (!session || res.status !== 200) throw new Error('MCP initialization failed');
  return session;
}
export default function (session) {
  const res = rpc('tools/call', { name: 'benchmark_read', arguments: {id:'ORD-BENCH-01'} }, session);
  const ns = Number(res.headers['X-Gateway-Overhead-Ns']);
  let ok = false;
  try {
    const line = res.body.split('\n').find(v => v.startsWith('data:'));
    const result = JSON.parse(line ? line.slice(5) : res.body).result;
    ok = res.status === 200 && !result.isError && Number.isFinite(ns) && ns >= 0 &&
      JSON.parse(result.content[0].text).orderId === 'ORD-BENCH-01';
  } catch (_) { ok = false; }
  valid.add(ok);
  if (ok) { completed.add(1); overhead.add(ns / 1000000); }
}
