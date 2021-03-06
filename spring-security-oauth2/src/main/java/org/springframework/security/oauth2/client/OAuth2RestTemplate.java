package org.springframework.security.oauth2.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Arrays;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.security.oauth2.client.context.DefaultOAuth2ClientContext;
import org.springframework.security.oauth2.client.context.OAuth2ClientContext;
import org.springframework.security.oauth2.client.http.AccessTokenRequiredException;
import org.springframework.security.oauth2.client.http.OAuth2ErrorHandler;
import org.springframework.security.oauth2.client.resource.OAuth2AccessDeniedException;
import org.springframework.security.oauth2.client.resource.OAuth2ProtectedResourceDetails;
import org.springframework.security.oauth2.client.token.AccessTokenProvider;
import org.springframework.security.oauth2.client.token.AccessTokenProviderChain;
import org.springframework.security.oauth2.client.token.AccessTokenRequest;
import org.springframework.security.oauth2.client.token.grant.client.ClientCredentialsAccessTokenProvider;
import org.springframework.security.oauth2.client.token.grant.code.AuthorizationCodeAccessTokenProvider;
import org.springframework.security.oauth2.client.token.grant.implicit.ImplicitAccessTokenProvider;
import org.springframework.security.oauth2.client.token.grant.password.ResourceOwnerPasswordAccessTokenProvider;
import org.springframework.security.oauth2.common.AuthenticationScheme;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Rest template that is able to make OAuth2-authenticated REST requests with the credentials of the provided resource.
 * 
 * @author Ryan Heaton
 * @author Dave Syer
 */
public class OAuth2RestTemplate extends RestTemplate {

	private final OAuth2ProtectedResourceDetails resource;

	private AccessTokenProvider accessTokenProvider = new AccessTokenProviderChain(Arrays.<AccessTokenProvider> asList(
			new AuthorizationCodeAccessTokenProvider(), new ImplicitAccessTokenProvider(),
			new ResourceOwnerPasswordAccessTokenProvider(), new ClientCredentialsAccessTokenProvider()));

	private OAuth2ClientContext context;

	private boolean retryBadAccessTokens = true;

	public OAuth2RestTemplate(OAuth2ProtectedResourceDetails resource) {
		this(resource, new DefaultOAuth2ClientContext());
	}

	public OAuth2RestTemplate(OAuth2ProtectedResourceDetails resource, OAuth2ClientContext context) {
		super();
		if (resource == null) {
			throw new IllegalArgumentException("An OAuth2 resource must be supplied.");
		}

		this.resource = resource;
		this.context = context;
		setErrorHandler(new OAuth2ErrorHandler(resource));
	}

	/**
	 * Flag to determine whether a request that has an existing access token, and which then leads to an
	 * AccessTokenRequiredException should be retried (immediately, once). Useful if the remote server doesn't recognize
	 * an old token which is stored in the client, but is happy to re-grant it.
	 * 
	 * @param retryBadAccessTokens the flag to set (default true)
	 */
	public void setRetryBadAccessTokens(boolean retryBadAccessTokens) {
		this.retryBadAccessTokens = retryBadAccessTokens;
	}

	@Override
	public void setErrorHandler(ResponseErrorHandler errorHandler) {
		if (!(errorHandler instanceof OAuth2ErrorHandler)) {
			errorHandler = new OAuth2ErrorHandler(errorHandler, resource);
		}
		super.setErrorHandler(errorHandler);
	}

	@Override
	protected ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {

		OAuth2AccessToken accessToken = getAccessToken();

		String tokenType = accessToken.getTokenType();
		if (!StringUtils.hasText(tokenType)) {
			tokenType = OAuth2AccessToken.BEARER_TYPE; // we'll assume basic bearer token type if none is specified.
		}
		if (OAuth2AccessToken.BEARER_TYPE.equalsIgnoreCase(tokenType)
				|| OAuth2AccessToken.OAUTH2_TYPE.equalsIgnoreCase(tokenType)) {
			AuthenticationScheme bearerTokenMethod = resource.getAuthenticationScheme();
			if (AuthenticationScheme.query.equals(bearerTokenMethod)
					|| AuthenticationScheme.form.equals(bearerTokenMethod)) {
				uri = appendQueryParameter(uri, accessToken);
			}

			ClientHttpRequest req = super.createRequest(uri, method);

			if (AuthenticationScheme.header.equals(bearerTokenMethod)) {
				req.getHeaders().add("Authorization",
						String.format("%s %s", OAuth2AccessToken.BEARER_TYPE, accessToken.getValue()));
			}
			return req;
		}
		else {
			throw new OAuth2AccessDeniedException("Unsupported access token type: " + tokenType);
		}

	}

	@Override
	protected <T> T doExecute(URI url, HttpMethod method, RequestCallback requestCallback,
			ResponseExtractor<T> responseExtractor) throws RestClientException {
		OAuth2AccessToken accessToken = context.getAccessToken();
		try {
			return super.doExecute(url, method, requestCallback, responseExtractor);
		}
		catch (AccessTokenRequiredException e) {
			if (accessToken != null && retryBadAccessTokens) {
				context.setAccessToken(null);
				return super.doExecute(url, method, requestCallback, responseExtractor);
			}
			else {
				throw e;
			}
		}
	}

	/**
	 * Acquire or renew an access token for the current context if necessary. This method will be called automatically
	 * when a request is executed (and the result is cached), but can also be called as a standalone method to
	 * pre-populate the token.
	 * 
	 * @return an access token
	 */
	public OAuth2AccessToken getAccessToken() throws UserRedirectRequiredException {

		OAuth2AccessToken accessToken = context.getAccessToken();

		if (accessToken == null || accessToken.isExpired()) {
			try {
				accessToken = acquireAccessToken(context);
			}
			catch (UserRedirectRequiredException e) {
				context.setAccessToken(null); // No point hanging onto it now
				accessToken = null;
				String stateKey = e.getStateKey();
				if (stateKey != null) {
					Object stateToPreserve = e.getStateToPreserve();
					if (stateToPreserve == null) {
						stateToPreserve = "state";
					}
					context.setPreservedState(stateKey, stateToPreserve);
				}
				throw e;
			}
		}
		return accessToken;
	}

	/**
	 * @return the context for this template
	 */
	public OAuth2ClientContext getOAuth2ClientContext() {
		return context;
	}

	protected OAuth2AccessToken acquireAccessToken(OAuth2ClientContext oauth2Context)
			throws UserRedirectRequiredException {

		AccessTokenRequest accessTokenRequest = oauth2Context.getAccessTokenRequest();
		if (accessTokenRequest == null) {
			throw new AccessTokenRequiredException(
					"No OAuth 2 security context has been established. Unable to access resource '"
							+ this.resource.getId() + "'.", resource);
		}

		// Transfer the preserved state from the (longer lived) context to the current request.
		String stateKey = accessTokenRequest.getStateKey();
		if (stateKey != null) {
			accessTokenRequest.setPreservedState(oauth2Context.removePreservedState(stateKey));
		}

		OAuth2AccessToken existingToken = oauth2Context.getAccessToken();
		if (existingToken != null) {
			accessTokenRequest.setExistingToken(existingToken);
		}

		OAuth2AccessToken accessToken = null;
		accessToken = accessTokenProvider.obtainAccessToken(resource, accessTokenRequest);
		if (accessToken == null || accessToken.getValue() == null) {
			throw new IllegalStateException(
					"Access token provider returned a null access token, which is illegal according to the contract.");
		}
		oauth2Context.setAccessToken(accessToken);
		return accessToken;
	}

	protected URI appendQueryParameter(URI uri, OAuth2AccessToken accessToken) {

		try {

			// TODO: there is some duplication with UriUtils here. Probably unavoidable as long as this
			// method signature uses URI not String.
			String query = uri.getQuery();
			String queryFragment = resource.getTokenName() + "=" + URLEncoder.encode(accessToken.getValue(), "UTF-8");
			if (query == null) {
				query = queryFragment;
			}
			else {
				query = query + "&" + queryFragment;
			}

			// first form the URI without query and fragment parts, so that it doesn't re-encode some query string chars
			// (SECOAUTH-90)
			URI update = new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(), null,
					null);
			// now add the encoded query string and the then fragment
			StringBuffer sb = new StringBuffer(update.toString());
			sb.append("?");
			sb.append(query);
			if (uri.getFragment() != null) {
				sb.append("#");
				sb.append(uri.getFragment());
			}

			return new URI(sb.toString());

		}
		catch (URISyntaxException e) {
			throw new IllegalArgumentException("Could not parse URI", e);
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalArgumentException("Could not encode URI", e);
		}

	}

	public void setAccessTokenProvider(AccessTokenProvider accessTokenProvider) {
		this.accessTokenProvider = accessTokenProvider;
	}

}
