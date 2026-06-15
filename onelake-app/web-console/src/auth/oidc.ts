const ACCESS_TOKEN_KEY = 'onelake.access_token';
const REFRESH_TOKEN_KEY = 'onelake.refresh_token';
const ID_TOKEN_KEY = 'onelake.id_token';
const EXPIRES_AT_KEY = 'onelake.expires_at';
const STATE_KEY = 'onelake.oidc_state';
const VERIFIER_KEY = 'onelake.oidc_code_verifier';
const RETURN_TO_KEY = 'onelake.oidc_return_to';

const authority = import.meta.env.VITE_OIDC_AUTHORITY || 'http://localhost:8081/realms/onelake';
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID || 'onelake-app';

export interface AuthUser {
  id: string;
  name: string;
  username: string;
  roles: string[];
  tenantId?: string;
}

interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_in?: number;
}

function redirectUri() {
  return `${window.location.origin}/sso/callback`;
}

function tokenEndpoint() {
  return `${authority}/protocol/openid-connect/token`;
}

function authorizationEndpoint() {
  return `${authority}/protocol/openid-connect/auth`;
}

function logoutEndpoint() {
  return `${authority}/protocol/openid-connect/logout`;
}

function randomString(bytes = 32) {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  return base64Url(data);
}

function base64Url(data: ArrayBuffer | Uint8Array) {
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);
  let binary = '';
  bytes.forEach((b) => {
    binary += String.fromCharCode(b);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

async function codeChallenge(verifier: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(digest);
}

function storeTokens(tokens: TokenResponse) {
  localStorage.setItem(ACCESS_TOKEN_KEY, tokens.access_token);
  if (tokens.refresh_token) localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refresh_token);
  if (tokens.id_token) localStorage.setItem(ID_TOKEN_KEY, tokens.id_token);
  const expiresIn = tokens.expires_in ?? 300;
  localStorage.setItem(EXPIRES_AT_KEY, String(Date.now() + expiresIn * 1000));
}

function decodeJwt(token: string) {
  const payload = token.split('.')[1];
  if (!payload) return {};
  const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
  return JSON.parse(atob(padded));
}

export function clearAuth() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(ID_TOKEN_KEY);
  localStorage.removeItem(EXPIRES_AT_KEY);
}

export function hasAccessToken() {
  return Boolean(localStorage.getItem(ACCESS_TOKEN_KEY));
}

export function getAuthUser(): AuthUser | null {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (!token) return null;
  const claims = decodeJwt(token) as {
    sub?: string;
    name?: string;
    preferred_username?: string;
    tenant_id?: string;
    realm_access?: { roles?: string[] };
  };
  const roles = claims.realm_access?.roles?.filter((role) => !role.startsWith('default-roles-') && role !== 'offline_access' && role !== 'uma_authorization') || [];
  const username = claims.preferred_username || 'unknown';
  return {
    id: claims.sub || username,
    name: claims.name || username,
    username,
    roles,
    tenantId: claims.tenant_id,
  };
}

export async function getValidAccessToken() {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  if (!token) return null;
  const expiresAt = Number(localStorage.getItem(EXPIRES_AT_KEY) || '0');
  if (expiresAt > Date.now() + 30_000) return token;

  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!refreshToken) return token;

  const body = new URLSearchParams({
    client_id: clientId,
    grant_type: 'refresh_token',
    refresh_token: refreshToken,
  });
  const response = await fetch(tokenEndpoint(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!response.ok) {
    clearAuth();
    return null;
  }
  storeTokens(await response.json());
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export async function startLogin(returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`) {
  const state = randomString(16);
  const verifier = randomString(48);
  sessionStorage.setItem(STATE_KEY, state);
  sessionStorage.setItem(VERIFIER_KEY, verifier);
  sessionStorage.setItem(RETURN_TO_KEY, returnTo || '/dashboard');

  const params = new URLSearchParams({
    client_id: clientId,
    response_type: 'code',
    scope: 'openid profile email',
    redirect_uri: redirectUri(),
    state,
    code_challenge: await codeChallenge(verifier),
    code_challenge_method: 'S256',
  });
  window.location.assign(`${authorizationEndpoint()}?${params.toString()}`);
}

export async function handleLoginCallback(search: string) {
  const params = new URLSearchParams(search);
  const code = params.get('code');
  const state = params.get('state');
  const expectedState = sessionStorage.getItem(STATE_KEY);
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  if (!code || !state || state !== expectedState || !verifier) {
    throw new Error('登录回调校验失败');
  }

  const body = new URLSearchParams({
    client_id: clientId,
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri(),
    code_verifier: verifier,
  });
  const response = await fetch(tokenEndpoint(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!response.ok) {
    throw new Error('登录令牌交换失败');
  }

  storeTokens(await response.json());
  const returnTo = sessionStorage.getItem(RETURN_TO_KEY) || '/dashboard';
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
  sessionStorage.removeItem(RETURN_TO_KEY);
  return returnTo;
}

export function logout() {
  const idToken = localStorage.getItem(ID_TOKEN_KEY);
  clearAuth();
  const params = new URLSearchParams({
    client_id: clientId,
    post_logout_redirect_uri: `${window.location.origin}/dashboard`,
  });
  if (idToken) params.set('id_token_hint', idToken);
  window.location.assign(`${logoutEndpoint()}?${params.toString()}`);
}
