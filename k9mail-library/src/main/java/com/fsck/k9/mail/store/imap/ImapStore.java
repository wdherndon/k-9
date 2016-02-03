
package com.fsck.k9.mail.store.imap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.text.SimpleDateFormat;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import android.net.ConnectivityManager;
import android.util.Log;

import com.fsck.k9.mail.NetworkType;
import com.fsck.k9.mail.AuthType;
import com.fsck.k9.mail.ConnectionSecurity;
import com.fsck.k9.mail.Flag;
import com.fsck.k9.mail.Folder;
import com.fsck.k9.mail.K9MailLib;
import com.fsck.k9.mail.MessagingException;
import com.fsck.k9.mail.PushReceiver;
import com.fsck.k9.mail.Pusher;
import com.fsck.k9.mail.ServerSettings;
import com.fsck.k9.mail.ssl.TrustedSocketFactory;
import com.fsck.k9.mail.store.RemoteStore;
import com.fsck.k9.mail.store.StoreConfig;

import com.beetstra.jutf7.CharsetProvider;

import static com.fsck.k9.mail.K9MailLib.LOG_TAG;


/**
 * <pre>
 * TODO Need to start keeping track of UIDVALIDITY
 * TODO Need a default response handler for things like folder updates
 * </pre>
 */
public class ImapStore extends RemoteStore {
    private Set<Flag> mPermanentFlagsIndex = EnumSet.noneOf(Flag.class);
    private ConnectivityManager mConnectivityManager;

    private String mHost;
    private int mPort;
    private String mUsername;
    private String mPassword;
    private String mClientCertificateAlias;
    private ConnectionSecurity mConnectionSecurity;
    private AuthType mAuthType;
    private String mPathPrefix;
    private String mCombinedPrefix = null;
    private String mPathDelimiter = null;

    public static ImapStoreSettings decodeUri(String uri) {
        return ImapStoreUriDecoder.decode(uri);
    }

    public static String createUri(ServerSettings server) {
        return ImapStoreUriCreator.create(server);
    }


    protected static final SimpleDateFormat RFC3501_DATE = new SimpleDateFormat("dd-MMM-yyyy", Locale.US);

    private final Deque<ImapConnection> mConnections =
        new LinkedList<ImapConnection>();

    /**
     * Charset used for converting folder names to and from UTF-7 as defined by RFC 3501.
     */
    private Charset mModifiedUtf7Charset;

    /**
     * Cache of ImapFolder objects. ImapFolders are attached to a given folder on the server
     * and as long as their associated connection remains open they are reusable between
     * requests. This cache lets us make sure we always reuse, if possible, for a given
     * folder name.
     */
    private final Map<String, ImapFolder> mFolderCache = new HashMap<String, ImapFolder>();

    public ImapStore(StoreConfig storeConfig,
                     TrustedSocketFactory trustedSocketFactory,
                     ConnectivityManager connectivityManager)
            throws MessagingException {
        super(storeConfig, trustedSocketFactory);

        ImapStoreSettings settings;
        try {
            settings = decodeUri(storeConfig.getStoreUri());
        } catch (IllegalArgumentException e) {
            throw new MessagingException("Error while decoding store URI", e);
        }

        mHost = settings.host;
        mPort = settings.port;

        mConnectionSecurity = settings.connectionSecurity;
        mConnectivityManager = connectivityManager;

        mAuthType = settings.authenticationType;
        mUsername = settings.username;
        mPassword = settings.password;
        mClientCertificateAlias = settings.clientCertificateAlias;

        // Make extra sure mPathPrefix is null if "auto-detect namespace" is configured
        mPathPrefix = (settings.autoDetectNamespace) ? null : settings.pathPrefix;

        mModifiedUtf7Charset = new CharsetProvider().charsetForName("X-RFC-3501");
    }

    @Override
    public Folder getFolder(String name) {
        ImapFolder folder;
        synchronized (mFolderCache) {
            folder = mFolderCache.get(name);
            if (folder == null) {
                folder = new ImapFolder(this, name);
                mFolderCache.put(name, folder);
            }
        }
        return folder;
    }

    String getCombinedPrefix() {
        if (mCombinedPrefix == null) {
            if (mPathPrefix != null) {
                String tmpPrefix = mPathPrefix.trim();
                String tmpDelim = (mPathDelimiter != null ? mPathDelimiter.trim() : "");
                if (tmpPrefix.endsWith(tmpDelim)) {
                    mCombinedPrefix = tmpPrefix;
                } else if (tmpPrefix.length() > 0) {
                    mCombinedPrefix = tmpPrefix + tmpDelim;
                } else {
                    mCombinedPrefix = "";
                }
            } else {
                mCombinedPrefix = "";
            }
        }
        return mCombinedPrefix;
    }

    @Override
    public List <? extends Folder > getPersonalNamespaces(boolean forceListAll) throws MessagingException {
        ImapConnection connection = getConnection();
        try {
            List <? extends Folder > allFolders = listFolders(connection, false);
            if (forceListAll || !mStoreConfig.subscribedFoldersOnly()) {
                return allFolders;
            } else {
                List<Folder> resultFolders = new LinkedList<Folder>();
                Set<String> subscribedFolderNames = new HashSet<String>();
                List <? extends Folder > subscribedFolders = listFolders(connection, true);
                for (Folder subscribedFolder : subscribedFolders) {
                    subscribedFolderNames.add(subscribedFolder.getName());
                }
                for (Folder folder : allFolders) {
                    if (subscribedFolderNames.contains(folder.getName())) {
                        resultFolders.add(folder);
                    }
                }
                return resultFolders;
            }
        } catch (IOException ioe) {
            connection.close();
            throw new MessagingException("Unable to get folder list.", ioe);
        } catch (MessagingException me) {
            connection.close();
            throw new MessagingException("Unable to get folder list.", me);
        } finally {
            releaseConnection(connection);
        }
    }


    private List <? extends Folder > listFolders(ImapConnection connection, boolean LSUB) throws IOException, MessagingException {
        String commandResponse = LSUB ? "LSUB" : "LIST";

        LinkedList<Folder> folders = new LinkedList<Folder>();

        List<ImapResponse> responses =
            connection.executeSimpleCommand(String.format("%s \"\" %s", commandResponse,
                                            encodeString(getCombinedPrefix() + "*")));

        List<ListResponse> listResponses = (LSUB) ?
                ListResponse.parseLsub(responses) : ListResponse.parseList(responses);

        for (ListResponse listResponse : listResponses) {
            boolean includeFolder = true;

            String decodedFolderName;
            try {
                decodedFolderName = decodeFolderName(listResponse.getName());
            } catch (CharacterCodingException e) {
                Log.w(LOG_TAG, "Folder name not correctly encoded with the UTF-7 variant " +
                      "as defined by RFC 3501: " + listResponse.getName(), e);

                //TODO: Use the raw name returned by the server for all commands that require
                //      a folder name. Use the decoded name only for showing it to the user.

                // We currently just skip folders with malformed names.
                continue;
            }

            String folder = decodedFolderName;

            if (mPathDelimiter == null) {
                mPathDelimiter = listResponse.getHierarchyDelimiter();
                mCombinedPrefix = null;
            }

            if (folder.equalsIgnoreCase(mStoreConfig.getInboxFolderName())) {
                continue;
            } else if (folder.equals(mStoreConfig.getOutboxFolderName())) {
                /*
                 * There is a folder on the server with the same name as our local
                 * outbox. Until we have a good plan to deal with this situation
                 * we simply ignore the folder on the server.
                 */
                continue;
            } else {
                int prefixLength = getCombinedPrefix().length();
                if (prefixLength > 0) {
                    // Strip prefix from the folder name
                    if (folder.length() >= prefixLength) {
                        folder = folder.substring(prefixLength);
                    }
                    if (!decodedFolderName.equalsIgnoreCase(getCombinedPrefix() + folder)) {
                        includeFolder = false;
                    }
                }
            }

            if (listResponse.hasAttribute("\\NoSelect")) {
                includeFolder = false;
            }

            if (includeFolder) {
                folders.add(getFolder(folder));
            }
        }

        folders.add(getFolder(mStoreConfig.getInboxFolderName()));
        return folders;

    }

    /**
     * Attempt to auto-configure folders by attributes if the server advertises that capability.
     *
     * The parsing here is essentially the same as
     * {@link #listFolders(ImapConnection, boolean)}; we should try to consolidate
     * this at some point. :(
     * @param connection IMAP Connection
     * @throws IOException uh oh!
     * @throws MessagingException uh oh!
     */
    void autoconfigureFolders(final ImapConnection connection) throws IOException, MessagingException {
        if (!connection.hasCapability(Capabilities.SPECIAL_USE)) {
            if (K9MailLib.isDebug()) {
                Log.d(LOG_TAG, "No detected folder auto-configuration methods.");
            }
            return;
        }

        if (K9MailLib.isDebug()) {
            Log.d(LOG_TAG, "Folder auto-configuration: Using RFC6154/SPECIAL-USE.");
        }

        String command = String.format("LIST (SPECIAL-USE) \"\" %s", encodeString(getCombinedPrefix() + "*"));
        List<ImapResponse> responses = connection.executeSimpleCommand(command);

        List<ListResponse> listResponses = ListResponse.parseList(responses);

        for (ListResponse listResponse : listResponses) {
            String decodedFolderName;
            try {
                decodedFolderName = decodeFolderName(listResponse.getName());
            } catch (CharacterCodingException e) {
                Log.w(LOG_TAG, "Folder name not correctly encoded with the UTF-7 variant " +
                    "as defined by RFC 3501: " + listResponse.getName(), e);
                // We currently just skip folders with malformed names.
                continue;
            }

            if (mPathDelimiter == null) {
                mPathDelimiter = listResponse.getHierarchyDelimiter();
                mCombinedPrefix = null;
            }

            if (listResponse.hasAttribute("\\Archive") || listResponse.hasAttribute("\\All")) {
                mStoreConfig.setArchiveFolderName(decodedFolderName);
                if (K9MailLib.isDebug()) {
                    Log.d(LOG_TAG, "Folder auto-configuration detected Archive folder: " + decodedFolderName);
                }
            } else if (listResponse.hasAttribute("\\Drafts")) {
                mStoreConfig.setDraftsFolderName(decodedFolderName);
                if (K9MailLib.isDebug()) {
                    Log.d(LOG_TAG, "Folder auto-configuration detected Drafts folder: " + decodedFolderName);
                }
            } else if (listResponse.hasAttribute("\\Sent")) {
                mStoreConfig.setSentFolderName(decodedFolderName);
                if (K9MailLib.isDebug()) {
                    Log.d(LOG_TAG, "Folder auto-configuration detected Sent folder: " + decodedFolderName);
                }
            } else if (listResponse.hasAttribute("\\Junk")) {
                mStoreConfig.setSpamFolderName(decodedFolderName);
                if (K9MailLib.isDebug()) {
                    Log.d(LOG_TAG, "Folder auto-configuration detected Spam folder: " + decodedFolderName);
                }
            } else if (listResponse.hasAttribute("\\Trash")) {
                mStoreConfig.setTrashFolderName(decodedFolderName);
                if (K9MailLib.isDebug()) {
                    Log.d(LOG_TAG, "Folder auto-configuration detected Trash folder: " + decodedFolderName);
                }
            }
        }
    }

    @Override
    public void checkSettings() throws MessagingException {
        try {
            ImapConnection connection = createImapConnection();

            connection.open();
            autoconfigureFolders(connection);
            connection.close();
        } catch (IOException ioe) {
            throw new MessagingException("Unable to connect", ioe);
        }
    }

    ImapConnection getConnection() throws MessagingException {
        synchronized (mConnections) {
            ImapConnection connection;
            while ((connection = mConnections.poll()) != null) {
                try {
                    connection.executeSimpleCommand(Commands.NOOP);
                    break;
                } catch (IOException ioe) {
                    connection.close();
                }
            }
            if (connection == null) {
                connection = createImapConnection();
            }
            return connection;
        }
    }

    void releaseConnection(ImapConnection connection) {
        if (connection != null && connection.isOpen()) {
            synchronized (mConnections) {
                mConnections.offer(connection);
            }
        }
    }

    ImapConnection createImapConnection() {
        return new ImapConnection(new StoreImapSettings(), mTrustedSocketFactory, mConnectivityManager);
    }

    /**
     * Encode a string to be able to use it in an IMAP command.
     *
     * "A quoted string is a sequence of zero or more 7-bit characters,
     *  excluding CR and LF, with double quote (<">) characters at each
     *  end." - Section 4.3, RFC 3501
     *
     * Double quotes and backslash are escaped by prepending a backslash.
     *
     * @param str
     *     The input string (only 7-bit characters allowed).
     * @return
     *     The string encoded as quoted (IMAP) string.
     */
    static String encodeString(String str) {
        return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    String encodeFolderName(String name) {
        ByteBuffer bb = mModifiedUtf7Charset.encode(name);
        byte[] b = new byte[bb.limit()];
        bb.get(b);
        return new String(b, Charset.forName("US-ASCII"));
    }

    private String decodeFolderName(String name) throws CharacterCodingException {
        /*
         * Convert the encoded name to US-ASCII, then pass it through the modified UTF-7
         * decoder and return the Unicode String.
         */
        // Make sure the decoder throws an exception if it encounters an invalid encoding.
        CharsetDecoder decoder = mModifiedUtf7Charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
        CharBuffer cb = decoder.decode(ByteBuffer.wrap(name.getBytes(Charset.forName("US-ASCII"))));
        return cb.toString();

    }

    @Override
    public boolean isMoveCapable() {
        return true;
    }

    @Override
    public boolean isCopyCapable() {
        return true;
    }
    @Override
    public boolean isPushCapable() {
        return true;
    }
    @Override
    public boolean isExpungeCapable() {
        return true;
    }

    StoreConfig getStoreConfig() {
        return mStoreConfig;
    }

    Set<Flag> getPermanentFlagsIndex() {
        return mPermanentFlagsIndex;
    }

    @Override
    public Pusher getPusher(PushReceiver receiver) {
        return new ImapPusher(this, receiver);
    }

    private class StoreImapSettings implements ImapSettings {
        @Override
        public String getHost() {
            return mHost;
        }

        @Override
        public int getPort() {
            return mPort;
        }

        @Override
        public ConnectionSecurity getConnectionSecurity() {
            return mConnectionSecurity;
        }

        @Override
        public AuthType getAuthType() {
            return mAuthType;
        }

        @Override
        public String getUsername() {
            return mUsername;
        }

        @Override
        public String getPassword() {
            return mPassword;
        }

        @Override
        public String getClientCertificateAlias() {
            return mClientCertificateAlias;
        }

        @Override
        public boolean useCompression(final NetworkType type) {
            return mStoreConfig.useCompression(type);
        }

        @Override
        public String getPathPrefix() {
            return mPathPrefix;
        }

        @Override
        public void setPathPrefix(String prefix) {
            mPathPrefix = prefix;
        }

        @Override
        public String getPathDelimiter() {
            return mPathDelimiter;
        }

        @Override
        public void setPathDelimiter(String delimiter) {
            mPathDelimiter = delimiter;
        }

        @Override
        public String getCombinedPrefix() {
            return mCombinedPrefix;
        }

        @Override
        public void setCombinedPrefix(String prefix) {
            mCombinedPrefix = prefix;
        }
    }
}
