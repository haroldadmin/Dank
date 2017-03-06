package me.saket.dank.data;

import android.content.Context;
import android.support.annotation.NonNull;

import net.dean.jraw.RedditClient;
import net.dean.jraw.auth.AuthenticationManager;
import net.dean.jraw.auth.AuthenticationState;
import net.dean.jraw.auth.RefreshTokenHandler;
import net.dean.jraw.http.LoggingMode;
import net.dean.jraw.http.NetworkException;
import net.dean.jraw.http.SubmissionRequest;
import net.dean.jraw.http.oauth.Credentials;
import net.dean.jraw.http.oauth.OAuthData;
import net.dean.jraw.http.oauth.OAuthHelper;
import net.dean.jraw.models.CommentNode;
import net.dean.jraw.models.CommentSort;
import net.dean.jraw.models.LoggedInAccount;
import net.dean.jraw.models.Submission;
import net.dean.jraw.models.Subreddit;
import net.dean.jraw.paginators.SubredditPaginator;
import net.dean.jraw.paginators.UserSubredditsPaginator;

import java.util.ArrayList;
import java.util.List;

import me.saket.dank.R;
import me.saket.dank.di.Dank;
import me.saket.dank.utils.AndroidTokenStore;
import rx.Observable;
import rx.functions.Func1;

/**
 * Wrapper around {@link RedditClient}.
 */
public class DankRedditClient {

    private final Context context;
    private final RedditClient redditClient;
    private final AuthenticationManager redditAuthManager;
    private final Credentials loggedInUserCredentials;
    private final Credentials userlessAppCredentials;

    private boolean authManagerInitialized;

    public DankRedditClient(Context context, RedditClient redditClient, AuthenticationManager redditAuthManager) {
        this.context = context;
        this.redditClient = redditClient;
        this.redditAuthManager = redditAuthManager;

        String redditAppClientId = context.getString(R.string.reddit_app_client_id);
        loggedInUserCredentials = Credentials.installedApp(redditAppClientId, context.getString(R.string.reddit_app_redirect_url));
        userlessAppCredentials = Credentials.userlessApp(redditAppClientId, Dank.sharedPrefs().getDeviceUuid());
    }

    public SubredditPaginator subredditPaginator(String subredditName) {
        return new SubredditPaginator(redditClient, subredditName);
    }

    /**
     * Get all details of submissions, including comments.
     * TODO: add support for comments sort mode.
     */
    public Observable<Submission> fullSubmissionData(Submission submission) {
        return Observable.fromCallable(() -> {
            CommentSort suggestedSort = submission.getSuggestedSort();
            if (suggestedSort == null) {
                suggestedSort = CommentSort.TOP;
            }

            return redditClient.getSubmission(
                    new SubmissionRequest.Builder(submission.getId())
                            .sort(suggestedSort)
                            .build()
            );
        });
    }

    /**
     * Load more replies of a comment node.
     */
    public Func1<CommentNode, CommentNode> loadMoreComments() {
        return commentNode -> {
            commentNode.loadMoreComments(redditClient);
            return commentNode;
        };
    }

    /**
     * Subreddits user has subscribed to.
     */
    public Observable<List<DankSubreddit>> userSubreddits() {
        return Observable.fromCallable(() -> {
            UserSubredditsPaginator subredditsPaginator = new UserSubredditsPaginator(redditClient, "subscriber");
            subredditsPaginator.setLimit(200);
            List<Subreddit> subreddits = subredditsPaginator.accumulateMergedAllSorted();
            List<DankSubreddit> dankSubreddits = new ArrayList<>(subreddits.size());
            for (Subreddit subreddit : subreddits) {
                dankSubreddits.add(DankSubreddit.create(subreddit.getDisplayName()));
            }
            return dankSubreddits;
        });
    }

// ======== AUTHENTICATION ======== //

    /**
     * Ensures that the app is authorized to make Reddit API calls and execute <var>wrappedObservable</var> to be specific.
     */
    public <T> Observable<T> withAuth(Observable<T> wrappedObservable) {
        return Dank.reddit()
                .authenticateIfNeeded()
                .flatMap(__ -> wrappedObservable)
                .retryWhen(Dank.reddit().refreshApiTokenAndRetryIfExpired());
    }

    /**
     * Get a new API token if we haven't already or refresh the existing API token if the last one has expired.
     */
    private Observable<Boolean> authenticateIfNeeded() {
        return Observable.fromCallable(() -> {
            if (!authManagerInitialized) {
                redditAuthManager.init(redditClient, new RefreshTokenHandler(new AndroidTokenStore(context), redditClient));
                authManagerInitialized = true;
            }

            // TODO: 10/02/17 Update this code for logged in user.

            AuthenticationState authState = redditAuthManager.checkAuthState();
            if (authState != AuthenticationState.READY) {
                switch (authState) {
                    case NONE:
                        //Timber.d("Authenticating app");
                        redditClient.authenticate(redditClient.getOAuthHelper().easyAuth(userlessAppCredentials));
                        break;

                    case NEED_REFRESH:
                        //Timber.d("Refreshing token");
                        redditAuthManager.refreshAccessToken(loggedInUserCredentials);
                        break;
                }
            }
            //else {
            //Timber.d("Already authenticated");
            //}

            return true;
        });
    }

    /**
     * Although refreshing token is already handled by {@link #authenticateIfNeeded()}, it is possible that the
     * token expires right after it returns and a Reddit API call is made. Or it is also possible that the access
     * token got revoked somehow and the server is returning a 401. In both cases, this method attempts to
     * re-authenticate.
     */
    @NonNull
    private Func1<Observable<? extends Throwable>, Observable<?>> refreshApiTokenAndRetryIfExpired() {
        return errors -> errors.flatMap(error -> {
            if (error instanceof NetworkException && ((NetworkException) error).getResponse().getStatusCode() == 401) {
                // Re-try authenticating.
                //Timber.w("Attempting to refresh token");
                return Observable.fromCallable(() -> {
                    redditAuthManager.refreshAccessToken(isUserLoggedIn() ? loggedInUserCredentials : userlessAppCredentials);
                    return true;
                });

            } else {
                return Observable.error(error);
            }
        });
    }

    public UserLoginHelper userLoginHelper() {
        return new UserLoginHelper();
    }

    public Observable<Boolean> logout() {
        return Observable.fromCallable(() -> {
            // Bug workaround: revokeAccessToken() method crashes if logging is enabled.
            LoggingMode modeBackup = redditClient.getLoggingMode();
            redditClient.setLoggingMode(LoggingMode.NEVER);

            redditClient.getOAuthHelper().revokeAccessToken(loggedInUserCredentials);

            redditClient.setLoggingMode(modeBackup);
            return true;
        });
    }

    public class UserLoginHelper {

        public UserLoginHelper() {
        }

        public String authorizationUrl() {
            String[] scopes = {
                    "account",
                    "edit",             // For editing comments and submissions
                    "history",
                    "identity",
                    "mysubreddits",
                    "privatemessages",
                    "read",
                    "report",           // For hiding or reporting a thread.
                    "save",
                    "submit",
                    "subscribe",
                    "vote",
                    "wikiread"
            };

            OAuthHelper oAuthHelper = redditClient.getOAuthHelper();
            return oAuthHelper.getAuthorizationUrl(loggedInUserCredentials, true /* permanent */, true /* useMobileSite */, scopes).toString();
        }

        /**
         * Emits an item when the app is successfully able to authenticate the user in.
         */
        public Observable<Boolean> parseOAuthSuccessUrl(String successUrl) {
            return Observable.fromCallable(() -> {
                OAuthData oAuthData = redditClient.getOAuthHelper().onUserChallenge(successUrl, loggedInUserCredentials);
                redditClient.authenticate(oAuthData);
                return true;
            });
        }

    }

// ======== USER ACCOUNT ======== //

    public String loggedInUserName() {
        return redditClient.getAuthenticatedUser();
    }

    public boolean isUserLoggedIn() {
        return redditClient.isAuthenticated() && redditClient.hasActiveUserContext();
    }

    public Observable<LoggedInAccount> loggedInUserAccount() {
        return Observable.fromCallable(() -> redditClient.me());
    }

}
