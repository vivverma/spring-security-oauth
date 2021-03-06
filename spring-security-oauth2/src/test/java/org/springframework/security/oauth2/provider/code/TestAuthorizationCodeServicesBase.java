package org.springframework.security.oauth2.provider.code;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.common.exceptions.InvalidGrantException;
import org.springframework.security.oauth2.provider.AuthorizationRequest;

public abstract class TestAuthorizationCodeServicesBase {

	abstract AuthorizationCodeServices getAuthorizationCodeServices();

	@Test
	public void testCreateAuthorizationCode() {
		AuthorizationRequestHolder expectedAuthentication = new AuthorizationRequestHolder(
				new AuthorizationRequest("id", null), new TestAuthentication(
						"test2", false)); 
		String code = getAuthorizationCodeServices().createAuthorizationCode(expectedAuthentication);
		assertNotNull(code);

		AuthorizationRequestHolder actualAuthentication = getAuthorizationCodeServices()
				.consumeAuthorizationCode(code);
		assertEquals(expectedAuthentication, actualAuthentication);
	}

	@Test
	public void testConsumeRemovesCode() {
		AuthorizationRequestHolder expectedAuthentication = new AuthorizationRequestHolder(
				new AuthorizationRequest("id", null), new TestAuthentication(
						"test2", false));
		String code = getAuthorizationCodeServices().createAuthorizationCode(expectedAuthentication);
		assertNotNull(code);

		AuthorizationRequestHolder actualAuthentication = getAuthorizationCodeServices()
				.consumeAuthorizationCode(code);
		assertEquals(expectedAuthentication, actualAuthentication);

		try {
			getAuthorizationCodeServices().consumeAuthorizationCode(code);
			fail("Should have thrown exception");
		} catch (InvalidGrantException e) {
			// good we expected this
		}
	}

	@Test
	public void testConsumeNonExistingCode() {
		try {
			getAuthorizationCodeServices().consumeAuthorizationCode("doesnt exist");
			fail("Should have thrown exception");
		} catch (InvalidGrantException e) {
			// good we expected this
		}
	}

	protected static class TestAuthentication extends AbstractAuthenticationToken {
		private String principal;
		public TestAuthentication(String name, boolean authenticated) {
			super(null);
			setAuthenticated(authenticated);
			this.principal = name;
		}

		public Object getCredentials() {
			return null;
		}
		
		public Object getPrincipal() {
			return this.principal;
		}
	}

}
