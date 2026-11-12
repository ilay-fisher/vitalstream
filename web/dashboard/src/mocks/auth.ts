import type { RoleName } from '../lib/auth';

/** What the mock backend needs from a token. */
export interface MockClaims {
  sub: string;
  name?: string;
  roles: RoleName[];
}

const encode = (value: unknown) =>
  btoa(JSON.stringify(value)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** An unsigned JWT-shaped token: enough for the MSW handlers to tell users and roles apart. */
export function issueMockToken(claims: MockClaims): string {
  return `${encode({ alg: 'none', typ: 'JWT' })}.${encode(claims)}.mock`;
}

/** Claims of a `Bearer` header produced by {@link issueMockToken}, or null for anything else. */
export function readMockToken(authorization: string | null): MockClaims | null {
  const token = /^Bearer (.+)$/.exec(authorization ?? '')?.[1];
  const payload = token?.split('.')[1];
  if (!payload) return null;
  try {
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const claims = JSON.parse(json) as Partial<MockClaims>;
    return typeof claims.sub === 'string' && Array.isArray(claims.roles)
      ? { sub: claims.sub, name: claims.name, roles: claims.roles }
      : null;
  } catch {
    return null;
  }
}
