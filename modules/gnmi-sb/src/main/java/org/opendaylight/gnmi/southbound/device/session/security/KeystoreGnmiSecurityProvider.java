/*
 * Copyright (c) 2021 PANTHEON.tech, s.r.o. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.gnmi.southbound.device.session.security;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.cert.CertificateException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.opendaylight.gnmi.connector.configuration.SecurityFactory;
import org.opendaylight.gnmi.connector.security.Security;
import org.opendaylight.gnmi.southbound.schema.certstore.service.CertificationStorageService;
import org.opendaylight.gnmi.southbound.timeout.TimeoutUtils;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.certificate.storage.rev210504.Keystore;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.GnmiNode;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.security.SecurityChoice;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.security.security.choice.InsecureDebugOnly;
import org.opendaylight.yang.gen.v1.urn.opendaylight.gnmi.topology.rev210316.security.security.choice.Secure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeystoreGnmiSecurityProvider implements GnmiSecurityProvider {

    private static final Logger LOG = LoggerFactory.getLogger(KeystoreGnmiSecurityProvider.class);
    private final CertificationStorageService certService;

    public KeystoreGnmiSecurityProvider(final CertificationStorageService certificationStorageService) {
        this.certService = certificationStorageService;
    }

    @Override
    public Security getSecurity(final GnmiNode gnmiNode) throws SessionSecurityException {
        final SecurityChoice securityChoice = gnmiNode.getConnectionParameters().getSecurityChoice();
        if (securityChoice instanceof Secure) {
            final String keystoreId = ((Secure) securityChoice).getKeystoreId();
            return getSecurityFromKeystoreId(keystoreId);
        } else if (securityChoice instanceof InsecureDebugOnly) {
            LOG.debug("Creating Security with insecure connection");
            return SecurityFactory.createInsecureGnmiSecurity();
        } else {
            throw new SessionSecurityException(
                    "Missing security configuration. Add keystoreId or connection-type parameter");
        }
    }

    private Security getSecurityFromKeystoreId(final String keystoreId)
            throws SessionSecurityException {
        final Optional<Keystore> optionalKeystore;
        try {
            optionalKeystore = this.certService.readCertificate(keystoreId)
                    .get(TimeoutUtils.DATASTORE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new SessionSecurityException(
                    String.format("Unable to read keystore [%s] certificates from operational datastore",
                            keystoreId), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionSecurityException(
                    String.format("Interrupted while reading keystore [%s] certificates from operational datastore",
                            keystoreId), e);
        }
        if (optionalKeystore.isPresent()) {
            LOG.debug("Creating Security from keystore [{}]", keystoreId);
            return getSecurityWithCertificates(optionalKeystore.orElseThrow());
        }
        throw new SessionSecurityException(
                String.format("Certificate with id [%s] is not found in datastore ", keystoreId));
    }

    private Security getSecurityWithCertificates(final Keystore keystore) throws SessionSecurityException {
        final KeyPair keyPair;
        if (keystore.getPassphrase() != null) {
            keyPair = getKeyPair(keystore.getClientKey(), keystore.getPassphrase());
        } else {
            keyPair = getKeyPair(keystore.getClientKey(), "");
        }
        return createSecurityFromKeystore(keyPair, keystore);
    }

    private Security createSecurityFromKeystore(final KeyPair keyPair, final Keystore keystore)
            throws SessionSecurityException {
        try {
            return SecurityFactory.createGnmiSecurity(keystore.getCaCertificate(), keystore.getClientCert(),
                    keyPair.getPrivate());
        } catch (CertificateException e) {
            throw new SessionSecurityException("Error while creating security with certificates", e);
        }
    }

    public static KeyPair decodePrivateKey(final Reader reader, final String passphrase) throws IOException {
        try (PEMParser keyReader = new PEMParser(reader)) {
            final Provider bcprov;
            final var prov = java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            bcprov = prov != null ? prov : new BouncyCastleProvider();

            final var converter = new JcaPEMKeyConverter().setProvider(bcprov);

            final PEMDecryptorProvider pemDecProv = new JcePEMDecryptorProviderBuilder()
                    .setProvider(bcprov)
                    .build(passphrase != null ? passphrase.toCharArray() : new char[0]);

            final Object obj = keyReader.readObject();
            if (obj == null) {
                throw new IOException("No PEM object found in input");
            }

            // Strict check that a PEM entry does not contain more than one key.
            if (keyReader.readObject() != null) {
                throw new IllegalStateException("Pem object contains multiple keys, "
                        + "make sure each PEM only contains one key.");
            }

            return getKeyPair(passphrase, obj, converter, pemDecProv, bcprov);
        }
    }

    private KeyPair getKeyPair(final String clientKey,
                               final String passphrase) throws SessionSecurityException {
        try {
            return decodePrivateKey(
                    new StringReader(this.certService
                            .decrypt(clientKey)
                            .replace("\\\\n", "\n")),
                    this.certService.decrypt(passphrase));
        } catch (IOException e) {
            throw new SessionSecurityException("Error while creating KeyPair from private key and passphrase", e);
        } catch (GeneralSecurityException e) {
            LOG.error("Failed do decrypt input {}", clientKey);
            throw new RuntimeException(e);
        }
    }

    private static KeyPair getKeyPair(String passphrase,
                                      Object obj,
                                      JcaPEMKeyConverter converter,
                                      PEMDecryptorProvider pemDecProv,
                                      Provider bcprov) throws IOException {
        switch (obj) {
            // 1) Traditional OpenSSL encrypted key pair: -----BEGIN ... PRIVATE KEY----- with Proc-Type/DEK-Info
            case PEMEncryptedKeyPair encKp -> {
                return converter.getKeyPair(encKp.decryptKeyPair(pemDecProv));
            }

            // 2) Traditional key pair
            case PEMKeyPair kp -> {
                return converter.getKeyPair(kp);
            }

            // 3) PKCS#8 unencrypted: -----BEGIN PRIVATE KEY-----
            case PrivateKeyInfo pki -> {
                final PrivateKey priv = converter.getPrivateKey(pki);
                return new KeyPair(null, priv);
            }

            // 4) PKCS#8 encrypted: -----BEGIN ENCRYPTED PRIVATE KEY-----
            case PKCS8EncryptedPrivateKeyInfo encP8 -> {
                try {
                    final var decProv = new JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider(bcprov)
                            .build(passphrase != null ? passphrase.toCharArray() : new char[0]);

                    final PrivateKeyInfo pki = encP8.decryptPrivateKeyInfo(decProv);
                    final PrivateKey priv = converter.getPrivateKey(pki);
                    return new KeyPair(null, priv);
                } catch (OperatorCreationException e) {
                    throw new IOException("Failed to decrypt PKCS#8 private key" + e.getLocalizedMessage(), e);

                } catch (PKCSException e) {
                    throw new IOException("Failed to decrypt PKCS#8 private key" + e.getLocalizedMessage(), e);
                }
            }
            default -> {
                // Common gotcha: some PEMs may contain cert/CSR first by mistake
                final String type = obj.getClass().getName();
                throw new IOException("Unsupported PEM object type for private key: " + type
                        + ". Ensure the input is a private key PEM (not a certificate/CSR/bundle).");
            }
        }
    }
}
