import { writable, derived, get } from 'svelte/store';
import { startRegistration, startAuthentication } from '@simplewebauthn/browser';
import type {
  PublicKeyCredentialCreationOptionsJSON,
  PublicKeyCredentialRequestOptionsJSON,
  RegistrationResponseJSON,
  AuthenticationResponseJSON,
} from '@simplewebauthn/browser';
import {
  saveCredential,
  getCredentials,
  deleteCredential,
  clearAll,
  type PasskeyCredential,
} from '../utils/passkey-storage.js';
import { logger } from '../utils/logger.js';

interface PasskeyState {
  isEnabled: boolean;
  credentials: PasskeyCredential[];
  requiresRegistration: boolean;
  isLoading: boolean;
  error: Error | null;
}

const initialState: PasskeyState = {
  isEnabled: false,
  credentials: [],
  requiresRegistration: false,
  isLoading: false,
  error: null,
};

const passkeyStore = writable<PasskeyState>(initialState);

function getRpId(): string {
  if (typeof window === 'undefined') {
    return 'localhost';
  }
  return window.location.hostname;
}

function getRpName(): string {
  return 'Yaytsa Music Player';
}

async function checkPasskeyRequired(): Promise<boolean> {
  passkeyStore.update(state => ({ ...state, isLoading: true, error: null }));

  try {
    const credentials = await getCredentials();
    const requiresRegistration = credentials.length === 0;

    passkeyStore.update(state => ({
      ...state,
      credentials,
      requiresRegistration,
      isEnabled: credentials.length > 0,
      isLoading: false,
    }));

    logger.info(`[Passkey] Credentials found: ${credentials.length}`);
    return requiresRegistration;
  } catch (error) {
    logger.error('[Passkey] checkPasskeyRequired error:', error);
    passkeyStore.update(state => ({
      ...state,
      isLoading: false,
      error: error instanceof Error ? error : new Error(String(error)),
    }));
    return false;
  }
}

async function registerPasskey(credentialName?: string): Promise<void> {
  passkeyStore.update(state => ({ ...state, isLoading: true, error: null }));

  try {
    if (!window.PublicKeyCredential) {
      throw new Error('WebAuthn not supported in this browser');
    }

    const userId = crypto.randomUUID();
    const challenge = new Uint8Array(32);
    crypto.getRandomValues(challenge);

    const creationOptions: PublicKeyCredentialCreationOptionsJSON = {
      challenge: bufferToBase64URL(challenge),
      rp: {
        name: getRpName(),
        id: getRpId(),
      },
      user: {
        id: bufferToBase64URL(new TextEncoder().encode(userId)),
        name: credentialName || 'user',
        displayName: credentialName || 'Passkey User',
      },
      pubKeyCredParams: [
        { alg: -7, type: 'public-key' },
        { alg: -257, type: 'public-key' },
      ],
      authenticatorSelection: {
        authenticatorAttachment: 'platform',
        requireResidentKey: true,
        residentKey: 'required',
        userVerification: 'required',
      },
      timeout: 60000,
      attestation: 'none',
    };

    logger.info('[Passkey] Starting registration with options:', creationOptions);

    const registrationResponse: RegistrationResponseJSON = await startRegistration({
      optionsJSON: creationOptions,
    });

    logger.info('[Passkey] Registration response received');

    const credentialId = registrationResponse.id;
    const publicKeyBytes = base64URLToBuffer(registrationResponse.response.publicKey || '');

    const credential: PasskeyCredential = {
      id: credentialId,
      publicKey: new Uint8Array(publicKeyBytes),
      counter: 0,
      transports: registrationResponse.response.transports || [],
      createdAt: new Date().toISOString(),
      name: credentialName || `Passkey ${new Date().toLocaleString()}`,
    };

    await saveCredential(credential);

    const updatedCredentials = await getCredentials();

    passkeyStore.update(state => ({
      ...state,
      isEnabled: true,
      credentials: updatedCredentials,
      requiresRegistration: false,
      isLoading: false,
    }));

    logger.info('[Passkey] Registration successful');
  } catch (error) {
    logger.error('[Passkey] Registration error:', error);
    passkeyStore.update(state => ({
      ...state,
      isLoading: false,
      error: error instanceof Error ? error : new Error(String(error)),
    }));
    throw error;
  }
}

async function verifyPasskey(): Promise<boolean> {
  passkeyStore.update(state => ({ ...state, isLoading: true, error: null }));

  try {
    if (!window.PublicKeyCredential) {
      throw new Error('WebAuthn not supported in this browser');
    }

    const credentials = await getCredentials();
    if (credentials.length === 0) {
      throw new Error('No passkeys registered');
    }

    const challenge = new Uint8Array(32);
    crypto.getRandomValues(challenge);

    const authenticationOptions: PublicKeyCredentialRequestOptionsJSON = {
      challenge: bufferToBase64URL(challenge),
      timeout: 60000,
      rpId: getRpId(),
      allowCredentials: credentials.map(cred => ({
        id: cred.id,
        type: 'public-key',
        transports: cred.transports as AuthenticatorTransport[],
      })),
      userVerification: 'required',
    };

    logger.info('[Passkey] Starting authentication');

    const authenticationResponse: AuthenticationResponseJSON = await startAuthentication({
      optionsJSON: authenticationOptions,
    });

    logger.info('[Passkey] Authentication response received');

    const usedCredential = credentials.find(c => c.id === authenticationResponse.id);
    if (usedCredential) {
      usedCredential.counter += 1;
      await saveCredential(usedCredential);
    }

    passkeyStore.update(state => ({
      ...state,
      isLoading: false,
    }));

    logger.info('[Passkey] Verification successful');
    return true;
  } catch (error) {
    logger.error('[Passkey] Verification error:', error);
    passkeyStore.update(state => ({
      ...state,
      isLoading: false,
      error: error instanceof Error ? error : new Error(String(error)),
    }));
    return false;
  }
}

async function listCredentials(): Promise<PasskeyCredential[]> {
  try {
    const credentials = await getCredentials();
    passkeyStore.update(state => ({
      ...state,
      credentials,
    }));
    return credentials;
  } catch (error) {
    logger.error('[Passkey] listCredentials error:', error);
    return [];
  }
}

async function deleteCredentialById(id: string): Promise<void> {
  passkeyStore.update(state => ({ ...state, isLoading: true, error: null }));

  try {
    await deleteCredential(id);
    const updatedCredentials = await getCredentials();

    passkeyStore.update(state => ({
      ...state,
      credentials: updatedCredentials,
      requiresRegistration: updatedCredentials.length === 0,
      isEnabled: updatedCredentials.length > 0,
      isLoading: false,
    }));

    logger.info(`[Passkey] Deleted credential: ${id}`);
  } catch (error) {
    logger.error('[Passkey] deleteCredentialById error:', error);
    passkeyStore.update(state => ({
      ...state,
      isLoading: false,
      error: error instanceof Error ? error : new Error(String(error)),
    }));
    throw error;
  }
}

async function clearAllCredentials(): Promise<void> {
  passkeyStore.update(state => ({ ...state, isLoading: true, error: null }));

  try {
    await clearAll();

    passkeyStore.update(state => ({
      ...state,
      isEnabled: false,
      credentials: [],
      requiresRegistration: true,
      isLoading: false,
    }));

    logger.info('[Passkey] Cleared all credentials');
  } catch (error) {
    logger.error('[Passkey] clearAllCredentials error:', error);
    passkeyStore.update(state => ({
      ...state,
      isLoading: false,
      error: error instanceof Error ? error : new Error(String(error)),
    }));
    throw error;
  }
}

function bufferToBase64URL(buffer: ArrayBuffer | Uint8Array): string {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function base64URLToBuffer(base64url: string): ArrayBuffer {
  const base64 = base64url.replace(/-/g, '+').replace(/_/g, '/');
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const binary = atob(base64 + padding);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes.buffer;
}

export const isPasskeyEnabled = derived(passkeyStore, $passkey => $passkey.isEnabled);
export const passkeyCredentials = derived(passkeyStore, $passkey => $passkey.credentials);
export const passkeyRequiresRegistration = derived(
  passkeyStore,
  $passkey => $passkey.requiresRegistration
);
export const passkeyIsLoading = derived(passkeyStore, $passkey => $passkey.isLoading);
export const passkeyError = derived(passkeyStore, $passkey => $passkey.error);

export const passkey = {
  subscribe: passkeyStore.subscribe,
  checkPasskeyRequired,
  registerPasskey,
  verifyPasskey,
  listCredentials,
  deleteCredential: deleteCredentialById,
  clearAll: clearAllCredentials,
};
