package de.robertegenolf.sausageapi.push;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schlüssel für Push: .p8 von Apple (EC) und JSON-Schlüssel eines Google-Dienstkontos (RSA). */
class PushKeyTests {

	@Test
	void apnsSchluesselAusP8() throws Exception {
		var generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(256);
		byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
		String p8 = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(pkcs8)
				+ "\n-----END PRIVATE KEY-----\n";
		assertThat(ApnsSender.parse(p8).getAlgorithm()).isEqualTo("EC");
		// In Kubernetes-Secrets landen Zeilenumbrüche manchmal als „\n“
		assertThat(ApnsSender.parse(p8.replace("\n", "\\n"))).isNotNull();
		assertThatThrownBy(() -> ApnsSender.parse("kein schlüssel")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void firebaseDienstkontoAusJson() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		String pem = "-----BEGIN PRIVATE KEY-----\\n"
				+ Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
				+ "\\n-----END PRIVATE KEY-----\\n";
		String json = """
				{"type":"service_account","project_id":"wurst-weiser","client_email":"push@wurst-weiser.iam.gserviceaccount.com",
				 "private_key":"%s","token_uri":"https://oauth2.googleapis.com/token"}
				""".formatted(pem);
		assertThat(PushSender.parse(json)).isNotNull();
		assertThatThrownBy(() -> PushSender.parse("{}")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void ohneKonfigurationNurProtokolliert() {
		assertThat(new ApnsSender("", "", "", "de.wurstweiser.app", false).send("abc", "T", "B", java.util.Map.of()))
				.isEqualTo(PushSender.Result.DISABLED);
		assertThat(new PushSender("").send("abc", "T", "B", java.util.Map.of())).isEqualTo(PushSender.Result.DISABLED);
	}
}
