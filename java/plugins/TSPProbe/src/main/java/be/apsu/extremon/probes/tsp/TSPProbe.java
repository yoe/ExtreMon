/*   ExtreMon Project                                                      
 *   Copyright (C) 2012 Frank Marien                                  
 *   frank@apsu.be                                                         
 *                                                                         
 *   This file is part of ExtreMon.                                        
 *                                                                         
 *   ExtreMon is free software: you can redistribute it and/or modify      
 *   it under the terms of the GNU General Public License as published by  
 *   the Free Software Foundation, either version 3 of the License, or     
 *   (at your option) any later version.                                   
 *                                                                         
 *   ExtreMon is distributed in the hope that it will be useful,           
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         
 *   GNU General Public License for more details.                          
 *                                                                         
 *   You should have received a copy of the GNU General Public License     
 *   along with ExtreMon.  If not, see <http://www.gnu.org/licenses/>.     
 */

package be.apsu.extremon.probes.tsp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLConnection;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cmp.PKIStatus;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cryptopro.CryptoProObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.teletrust.TeleTrusTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.X509ObjectIdentifiers;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.tsp.TimeStampTokenInfo;
import org.bouncycastle.util.Store;

import be.apsu.extremon.STATE;
import be.apsu.extremon.plugin.X3Out;

public class TSPProbe extends X3Out {
    private static final int DEFAULT_DELAY = 1000;
    private static final String ALLOWED_SIGNATURE_CERTIFICATE_ALGORITHMS = "allowed.signature.certificate.algorithms";

    // probe config
    final private int delay;
    final private TimeStampRequestGenerator requestGenerator;
    final private URL url;
    final private SignerInformationVerifier signerVerifier;
    final private Random random;

    private boolean running;
    private Set<String> oidsAllowed;
    private Map<String, String> oidToName;

    public TSPProbe() throws Exception {
	this.delay = confInt("delay", DEFAULT_DELAY);
	this.running = false;
	getAllowedSignatureOIDs(confStr(
		ALLOWED_SIGNATURE_CERTIFICATE_ALGORITHMS).split(","));

	Security.addProvider(
		new org.bouncycastle.jce.provider.BouncyCastleProvider());

	url = new URL(confStr("url"));

	this.requestGenerator = new TimeStampRequestGenerator();
	this.requestGenerator.setCertReq(true);

	CertificateFactory certificateFactory = CertificateFactory
		.getInstance("X.509");
	String encodedCert = confStr("tsa.certificate");
	X509Certificate tsaCert = (X509Certificate) certificateFactory
		.generateCertificate(new ByteArrayInputStream(Base64
			.decodeBase64(encodedCert)));
	JcaSimpleSignerInfoVerifierBuilder verifierBuilder = 
		new JcaSimpleSignerInfoVerifierBuilder();
	this.signerVerifier = verifierBuilder.build(tsaCert);

	this.random = new Random();

	start();
	log("initialized");
    }

    private TimeStampResponse probe(TimeStampRequest request)
	    throws IOException, TSPException {
	URLConnection connection = this.url.openConnection();
	connection.setDoInput(true);
	connection.setDoOutput(true);
	connection.setUseCaches(false);
	connection.setRequestProperty("Content-Type",
		"application/timestamp-query");
	OutputStream outputStream = (connection.getOutputStream());
	outputStream.write(request.getEncoded());
	outputStream.flush();
	outputStream.close();
	InputStream inputStream = connection.getInputStream();
	TimeStampResponse response = new TimeStampResponse(inputStream);
	inputStream.close();
	return response;
    }

    public void probe_forever() {
	double start = 0, end = 0;
	BigInteger requestNonce;
	byte[] requestHashedMessage = new byte[20];
	List<String> comments = new ArrayList<String>();
	STATE result = STATE.OK;

	log("running");

	this.running = true;
	while (this.running) {
	    comments.clear();
	    this.random.nextBytes(requestHashedMessage);
	    requestNonce = new BigInteger(512, this.random);
	    TimeStampRequest request = requestGenerator.generate(
		    TSPAlgorithms.SHA1, requestHashedMessage,
		    requestNonce);

	    end = 0;
	    start = System.currentTimeMillis();

	    try {
		TimeStampResponse response = probe(request);

		switch (response.getStatus()) {
		case PKIStatus.GRANTED:
		    comments.add("granted");
		    result = STATE.OK;
		    break;
		case PKIStatus.GRANTED_WITH_MODS:
		    comments.add("granted with modifications");
		    result = STATE.WARNING;
		    break;
		case PKIStatus.REJECTION:
		    comments.add("rejected");
		    result = STATE.ALERT;
		    break;
		case PKIStatus.WAITING:
		    comments.add("waiting");
		    result = STATE.ALERT;
		    break;
		case PKIStatus.REVOCATION_WARNING:
		    comments.add("revocation warning");
		    result = STATE.WARNING;
		    break;
		case PKIStatus.REVOCATION_NOTIFICATION:
		    comments.add("revocation notification");
		    result = STATE.ALERT;
		    break;
		default:
		    comments.add("response outside RFC3161");
		    result = STATE.ALERT;
		    break;
		}

		if (response.getStatus() >= 2)
		    comments.add(response.getFailInfo() != null ? response
			    .getFailInfo().getString()
			    : "(missing failinfo)");

		if (response.getStatusString() != null)
		    comments.add(response.getStatusString());

		end = System.currentTimeMillis();
		TimeStampToken timestampToken = response
			.getTimeStampToken();

		timestampToken.validate(this.signerVerifier);
		comments.add("validated");

		AttributeTable table = timestampToken
			.getSignedAttributes();
		TimeStampTokenInfo tokenInfo = timestampToken
			.getTimeStampInfo();
		BigInteger responseNonce = tokenInfo.getNonce();
		byte[] responseHashedMessage = tokenInfo
			.getMessageImprintDigest();
		long genTimeSeconds=
			(tokenInfo.getGenTime().getTime())/1000;
		long currentTimeSeconds=
			(long)(start+((end-start)/2))/1000;

		put("clockskew",
			(genTimeSeconds - currentTimeSeconds) * 1000);

		if (Math.abs((genTimeSeconds - currentTimeSeconds)) > 1) {
		    comments.add("clock skew > 1s");
		    result = STATE.ALERT;
		}

		Store responseCertificatesStore = timestampToken
			.toCMSSignedData().getCertificates();
		@SuppressWarnings("unchecked")
		Collection<X509CertificateHolder> certs=
			responseCertificatesStore
				.getMatches(null);
		for (X509CertificateHolder certificate : certs) {
		    AlgorithmIdentifier sigalg = certificate
			    .getSignatureAlgorithm();
		    if (!(oidsAllowed.contains(sigalg.getAlgorithm()
			    .getId()))) {
			String cleanDn = certificate.getSubject()
				.toString().replace("=", ":");
			comments.add("signature cert \"" + cleanDn
				+ "\" signed using "
				+ getName(sigalg.getAlgorithm().getId()));
			result = STATE.ALERT;
		    }
		}

		if (!responseNonce.equals(requestNonce)) {
		    comments.add("nonce modified");
		    result = STATE.ALERT;
		}

		if (!Arrays.equals(responseHashedMessage,
			requestHashedMessage)) {
		    comments.add("hashed message modified");
		    result = STATE.ALERT;
		}

		if (table
			.get(PKCSObjectIdentifiers.id_aa_signingCertificate) == null) {
		    comments.add("signingcertificate missing");
		    result = STATE.ALERT;
		}
	    } catch (TSPException tspEx) {
		comments.add("validation failed");
		comments.add("tspexception-"
			+ tspEx.getMessage().toLowerCase());
		result = STATE.ALERT;
	    } catch (IOException iox) {
		comments.add("unable to obtain response");
		comments.add("ioexception-"
			+ iox.getMessage().toLowerCase());
		result = STATE.ALERT;
	    } catch (Exception ex) {
		comments.add("unhandled exception");
		result = STATE.ALERT;
	    } finally {
		if (end == 0)
		    end = System.currentTimeMillis();
	    }

	    put(RESULT_SUFFIX, result);
	    put(RESULT_COMMENT_SUFFIX, StringUtils.join(comments, "|"));
	    put("responsetime", (end - start));

	    try {
		Thread.sleep(this.delay);
	    } catch (InterruptedException ex) {
		log("interrupted");
	    }
	}
    }

    private String getName(String oid) {
	String name = this.oidToName.get(oid);
	if (name == null)
	    return oid;
	return name;
    }

    private void getAllowedSignatureOIDs(String[] names) {
	oidsAllowed = new HashSet<String>();
	oidToName = new HashMap<String, String>();

	for (Class<?> clazz : new Class[] { X9ObjectIdentifiers.class,
		OIWObjectIdentifiers.class, PKCSObjectIdentifiers.class,
		TeleTrusTObjectIdentifiers.class,
		X509ObjectIdentifiers.class,
		CMSSignedDataGenerator.class,
		CryptoProObjectIdentifiers.class }) {
	    for (Field field : clazz.getFields()) {
		if (field.getType().equals(ASN1ObjectIdentifier.class)
			&& field.getName().toLowerCase().contains("with")) {
		    try {
			ASN1ObjectIdentifier identifier = (ASN1ObjectIdentifier) field
				.get(null);
			String nameFound = field.getName().toLowerCase()
				.replace("_", "");
			oidToName.put(identifier.getId(), nameFound);
			for (String name : names) {
			    String nameAllowed = name.toLowerCase()
				    .replace("_", "");
			    if (nameAllowed.equals(nameFound)) {
				oidsAllowed.add(identifier.getId());
			    }
			}
		    } catch (IllegalArgumentException e) {
			// if interface changed, simply don't use
		    } catch (IllegalAccessException e) {
			// if private, simply don't use
		    }

		}
	    }
	}
    }

    public static void main(String[] args) throws Exception {
	new TSPProbe().probe_forever();
    }
}
