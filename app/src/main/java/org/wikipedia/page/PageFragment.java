package org.wikipedia.page;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomSheetDialog;
import android.support.design.widget.BottomSheetDialogFragment;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.view.ActionMode;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.appenguin.onboarding.ToolTip;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.wikipedia.BackPressedHandler;
import org.wikipedia.Constants;
import org.wikipedia.LongPressHandler;
import org.wikipedia.R;
import org.wikipedia.WikipediaApp;
import org.wikipedia.activity.FragmentUtil;
import org.wikipedia.analytics.FindInPageFunnel;
import org.wikipedia.analytics.GalleryFunnel;
import org.wikipedia.analytics.PageScrollFunnel;
import org.wikipedia.analytics.TabFunnel;
import org.wikipedia.bridge.CommunicationBridge;
import org.wikipedia.bridge.DarkModeMarshaller;
import org.wikipedia.concurrency.CallbackTask;
import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.okhttp.OkHttpWebViewClient;
import org.wikipedia.descriptions.DescriptionEditActivity;
import org.wikipedia.edit.EditHandler;
import org.wikipedia.gallery.GalleryActivity;
import org.wikipedia.history.HistoryEntry;
import org.wikipedia.history.UpdateHistoryTask;
import org.wikipedia.language.LangLinksActivity;
import org.wikipedia.offline.OfflineManager;
import org.wikipedia.onboarding.PrefsOnboardingStateMachine;
import org.wikipedia.page.action.PageActionTab;
import org.wikipedia.page.action.PageActionToolbarHideHandler;
import org.wikipedia.page.leadimages.LeadImagesHandler;
import org.wikipedia.page.leadimages.PageHeaderView;
import org.wikipedia.page.snippet.CompatActionMode;
import org.wikipedia.page.snippet.ShareHandler;
import org.wikipedia.page.tabs.Tab;
import org.wikipedia.page.tabs.TabsProvider;
import org.wikipedia.readinglist.AddToReadingListDialog;
import org.wikipedia.readinglist.ReadingList;
import org.wikipedia.readinglist.ReadingListBookmarkMenu;
import org.wikipedia.readinglist.page.ReadingListPage;
import org.wikipedia.readinglist.page.database.ReadingListDaoProxy;
import org.wikipedia.settings.Prefs;
import org.wikipedia.tooltip.ToolTipUtil;
import org.wikipedia.util.ActiveTimer;
import org.wikipedia.util.DeviceUtil;
import org.wikipedia.util.DimenUtil;
import org.wikipedia.util.FeedbackUtil;
import org.wikipedia.util.ReleaseUtil;
import org.wikipedia.util.ResourceUtil;
import org.wikipedia.util.ShareUtil;
import org.wikipedia.util.StringUtil;
import org.wikipedia.util.ThrowableUtil;
import org.wikipedia.util.UriUtil;
import org.wikipedia.util.log.L;
import org.wikipedia.views.ConfigurableTabLayout;
import org.wikipedia.views.ObservableWebView;
import org.wikipedia.views.SwipeRefreshLayoutWithScroll;
import org.wikipedia.views.WikiDrawerLayout;
import org.wikipedia.views.WikiPageErrorView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static android.app.Activity.RESULT_OK;
import static butterknife.ButterKnife.findById;
import static org.wikipedia.util.DimenUtil.getContentTopOffset;
import static org.wikipedia.util.DimenUtil.getContentTopOffsetPx;
import static org.wikipedia.util.ResourceUtil.getThemedAttributeId;
import static org.wikipedia.util.ResourceUtil.getThemedColor;
import static org.wikipedia.util.ThrowableUtil.isOffline;
import static org.wikipedia.util.UriUtil.decodeURL;
import static org.wikipedia.util.UriUtil.visitInExternalBrowser;

public class PageFragment extends Fragment implements BackPressedHandler {
    public interface Callback {
        void onPageShowBottomSheet(@NonNull BottomSheetDialog dialog);
        void onPageShowBottomSheet(@NonNull BottomSheetDialogFragment dialog);
        void onPageDismissBottomSheet();
        @Nullable PageToolbarHideHandler onPageGetToolbarHideHandler();
        void onPageLoadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry);
        void onPageLoadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry,
                            @NonNull TabsProvider.TabPosition tabPosition);
        void onPageShowLinkPreview(@NonNull PageTitle title, int source);
        void onPageLoadMainPageInForegroundTab();
        void onPageUpdateProgressBar(boolean visible, boolean indeterminate, int value);
        void onPageSearchRequested();
        boolean onPageIsSearching();
        @Nullable Fragment onPageGetTopFragment();
        void onPageShowThemeChooser();
        @Nullable ActionMode onPageStartSupportActionMode(@NonNull ActionMode.Callback callback);
        void onPageShowToolbar();
        void onPageHideSoftKeyboard();
        @Nullable PageLoadCallbacks onPageGetPageLoadCallbacks();
        void onPageAddToReadingList(@NonNull PageTitle title,
                                    @NonNull AddToReadingListDialog.InvokeSource source);
        void onPageRemoveFromReadingLists(@NonNull PageTitle title);
        @Nullable View onPageGetContentView();
        @Nullable View onPageGetTabsContainerView();
        void onPagePopFragment();
        @Nullable AppCompatActivity getActivity();
        void onPageInvalidateOptionsMenu();
        void onPageLoadError(@NonNull PageTitle title);
        void onPageLoadErrorRetry();
        void onPageLoadErrorBackPressed();
        boolean shouldLoadFromBackStack();
        boolean shouldShowTabList();
    }

    public static final int TOC_ACTION_SHOW = 0;
    public static final int TOC_ACTION_HIDE = 1;
    public static final int TOC_ACTION_TOGGLE = 2;

    private boolean pageRefreshed;
    private boolean errorState = false;

    private static final int REFRESH_SPINNER_ADDITIONAL_OFFSET = (int) (16 * DimenUtil.getDensityScalar());

    private PageFragmentLoadState pageFragmentLoadState;
    private PageViewModel model;
    @Nullable private PageInfo pageInfo;

    /**
     * List of tabs, each of which contains a backstack of page titles.
     * Since the list consists of Parcelable objects, it can be saved and restored from the
     * savedInstanceState of the fragment.
     */
    @NonNull
    private final List<Tab> tabList = new ArrayList<>();

    @NonNull
    private TabFunnel tabFunnel = new TabFunnel();

    @Nullable
    private PageScrollFunnel pageScrollFunnel;
    private LeadImagesHandler leadImagesHandler;
    private PageToolbarHideHandler toolbarHideHandler;
    private ObservableWebView webView;
    private SwipeRefreshLayoutWithScroll refreshView;
    private WikiPageErrorView errorView;
    private WikiDrawerLayout tocDrawer;
    private ConfigurableTabLayout tabLayout;

    private CommunicationBridge bridge;
    private LinkHandler linkHandler;
    private EditHandler editHandler;
    private ActionMode findInPageActionMode;
    @NonNull private ShareHandler shareHandler;
    private TabsProvider tabsProvider;
    private ActiveTimer activeTimer = new ActiveTimer();

    private WikipediaApp app;

    @NonNull
    private final SwipeRefreshLayout.OnRefreshListener pageRefreshListener = new SwipeRefreshLayout.OnRefreshListener() {
        @Override
        public void onRefresh() {
            refreshPage();
        }
    };

    @NonNull
    private final TabLayout.OnTabSelectedListener pageActionTabListener
            = new TabLayout.OnTabSelectedListener() {
        @Override
        public void onTabSelected(TabLayout.Tab tab) {
            if (tabLayout.isEnabled(tab)) {
                PageActionTab.of(tab.getPosition()).select(pageActionTabsCallback);
            }
        }

        @Override
        public void onTabUnselected(TabLayout.Tab tab) {

        }

        @Override
        public void onTabReselected(TabLayout.Tab tab) {
            onTabSelected(tab);
        }
    };

    private PageActionTab.Callback pageActionTabsCallback = new PageActionTab.Callback() {
        @Override
        public void onAddToReadingListTabSelected() {
            if (model.isInReadingList()) {
                new ReadingListBookmarkMenu(tabLayout, new ReadingListBookmarkMenu.Callback() {
                    @Override
                    public void onAddRequest(@Nullable ReadingListPage page) {
                        addToReadingList(AddToReadingListDialog.InvokeSource.BOOKMARK_BUTTON);
                    }

                    @Override
                    public void onDeleted(@Nullable ReadingListPage page) {
                        if (callback() != null) {
                            callback().onPageRemoveFromReadingLists(getTitle());
                        }
                    }
                }).show(getTitle());
            } else {
                addToReadingList(AddToReadingListDialog.InvokeSource.BOOKMARK_BUTTON);
            }
        }

        @Override
        public void onSharePageTabSelected() {
            sharePageLink();
        }

        @Override
        public void onChooseLangTabSelected() {
            startLangLinksActivity();
        }

        @Override
        public void onFindInPageTabSelected() {
            showFindInPage();
        }

        @Override
        public void onViewToCTabSelected() {
            toggleToC(TOC_ACTION_TOGGLE);
        }

        @Override
        public void updateBookmark(boolean pageSaved) {
            setBookmarkIconForPageSavedState(pageSaved);
        }
    };

    public ObservableWebView getWebView() {
        return webView;
    }

    public PageTitle getTitle() {
        return model.getTitle();
    }

    public PageTitle getTitleOriginal() {
        return model.getTitleOriginal();
    }

    @NonNull public ShareHandler getShareHandler() {
        return shareHandler;
    }

    @Nullable public Page getPage() {
        return model.getPage();
    }

    public HistoryEntry getHistoryEntry() {
        return model.getCurEntry();
    }

    public EditHandler getEditHandler() {
        return editHandler;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (WikipediaApp) getActivity().getApplicationContext();
        model = new PageViewModel();
        pageFragmentLoadState = new PageFragmentLoadState();

        initTabs();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             final Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_page, container, false);

        webView = (ObservableWebView) rootView.findViewById(R.id.page_web_view);
        initWebViewListeners();

        tocDrawer = (WikiDrawerLayout) rootView.findViewById(R.id.page_toc_drawer);
        tocDrawer.setDragEdgeWidth(getResources().getDimensionPixelSize(R.dimen.drawer_drag_margin));

        refreshView = (SwipeRefreshLayoutWithScroll) rootView
                .findViewById(R.id.page_refresh_container);
        int swipeOffset = getContentTopOffsetPx(getActivity()) + REFRESH_SPINNER_ADDITIONAL_OFFSET;
        refreshView.setProgressViewOffset(false, -swipeOffset, swipeOffset);
        refreshView.setColorSchemeResources(getThemedAttributeId(getContext(), R.attr.colorAccent));
        refreshView.setScrollableChild(webView);
        refreshView.setOnRefreshListener(pageRefreshListener);

        tabLayout = (ConfigurableTabLayout) rootView.findViewById(R.id.page_actions_tab_layout);
        tabLayout.addOnTabSelectedListener(pageActionTabListener);

        PageActionToolbarHideHandler pageActionToolbarHideHandler = new PageActionToolbarHideHandler(tabLayout);
        pageActionToolbarHideHandler.setScrollView(webView);

        errorView = (WikiPageErrorView) rootView.findViewById(R.id.page_error);

        return rootView;
    }

    @Override
    public void onDestroyView() {
        //uninitialize the bridge, so that no further JS events can have any effect.
        bridge.cleanup();
        tabsProvider.setTabsProviderListener(null);
        toolbarHideHandler.setScrollView(null);
        webView.destroy();
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        app.getRefWatcher().watch(this);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setHasOptionsMenu(true);

        updateFontSize();

        // Explicitly set background color of the WebView (independently of CSS, because
        // the background may be shown momentarily while the WebView loads content,
        // creating a seizure-inducing effect, or at the very least, a migraine with aura).
        webView.setBackgroundColor(getThemedColor(getActivity(), R.attr.page_background_color));

        bridge = new CommunicationBridge(webView, "file:///android_asset/index.html");
        setupMessageHandlers();
        sendDecorOffsetMessage();

        // make sure styles get injected before the DarkModeMarshaller and other handlers
        if (app.isCurrentThemeDark()) {
            new DarkModeMarshaller(bridge).turnOn(true);
        }

        errorView.setRetryClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callback() != null) {
                    callback().onPageLoadErrorRetry();
                }
                refreshPage();
            }
        });
        errorView.setBackClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean back = onBackPressed();

                // Needed if we're coming from another activity or fragment
                if (!back && callback() != null) {
                    // noinspection ConstantConditions
                    callback().onPageLoadErrorBackPressed();
                }
            }
        });

        editHandler = new EditHandler(this, bridge);
        pageFragmentLoadState.setEditHandler(editHandler);

        tocHandler = new ToCHandler(this, tocDrawer, bridge);

        // TODO: initialize View references in onCreateView().
        PageHeaderView pageHeaderView = findById(getView(), R.id.page_header_view);
        leadImagesHandler = new LeadImagesHandler(this, bridge, webView, pageHeaderView);
        toolbarHideHandler = getSearchBarHideHandler();
        toolbarHideHandler.setScrollView(webView);

        shareHandler = new ShareHandler(this, bridge);
        tabsProvider = new TabsProvider(this, tabList);
        tabsProvider.setTabsProviderListener(tabsProviderListener);

        if (callback() != null) {
            LongPressHandler.WebViewContextMenuListener contextMenuListener
                    = new PageFragmentLongPressHandler(callback());
            new LongPressHandler(webView, HistoryEntry.SOURCE_INTERNAL_LINK, contextMenuListener);
        }

        pageFragmentLoadState.setUp(model, this, refreshView, webView, bridge, toolbarHideHandler,
                leadImagesHandler, getCurrentTab().getBackStack());

        if (callback() != null) {
            if (savedInstanceState != null || callback().shouldLoadFromBackStack()) {
                pageFragmentLoadState.loadFromBackStack();
            }

            if (callback().shouldShowTabList()) {
                showTabList();
            }
        }
    }

    private void initWebViewListeners() {
        webView.addOnUpOrCancelMotionEventListener(new ObservableWebView.OnUpOrCancelMotionEventListener() {
            @Override
            public void onUpOrCancelMotionEvent() {
                // update our session, since it's possible for the user to remain on the page for
                // a long time, and we wouldn't want the session to time out.
                app.getSessionFunnel().touchSession();
            }
        });
        webView.addOnScrollChangeListener(new ObservableWebView.OnScrollChangeListener() {
            @Override
            public void onScrollChanged(int oldScrollY, int scrollY, boolean isHumanScroll) {
                if (pageScrollFunnel != null) {
                    pageScrollFunnel.onPageScrolled(oldScrollY, scrollY, isHumanScroll);
                }
            }
        });
        webView.setWebViewClient(new OkHttpWebViewClient());
    }

    private void handleInternalLink(@NonNull PageTitle title) {
        if (!isResumed()) {
            return;
        }
        // If it's a Special page, launch it in an external browser, since mobileview
        // doesn't support the Special namespace.
        // TODO: remove when Special pages are properly returned by the server
        // If this is a Talk page also show in external browser since we don't handle those pages
        // in the app very well at this time.
        if (title.isSpecial() || title.isTalkPage()) {
            visitInExternalBrowser(getActivity(), Uri.parse(title.getMobileUri()));
            return;
        }
        dismissBottomSheet();
        if (title.namespace() != Namespace.MAIN || !app.isLinkPreviewEnabled()
                || (!DeviceUtil.isOnline() && OfflineManager.instance().titleExists(title.getDisplayText()))) {
            HistoryEntry historyEntry = new HistoryEntry(title, HistoryEntry.SOURCE_INTERNAL_LINK);
            loadPage(title, historyEntry);
        } else {
            showLinkPreview(title, HistoryEntry.SOURCE_INTERNAL_LINK);
        }
    }

    private TabsProvider.TabsProviderListener tabsProviderListener = new TabsProvider.TabsProviderListener() {
        @Override
        public void onEnterTabView() {
            tabFunnel = new TabFunnel();
            tabFunnel.logEnterList(tabList.size());
            leadImagesHandler.setAnimationPaused(true);
        }

        @Override
        public void onCancelTabView() {
            tabsProvider.exitTabMode();
            tabFunnel.logCancel(tabList.size());
            leadImagesHandler.setAnimationPaused(false);
            if (tabsProvider.shouldPopFragment()) {
                Callback callback = callback();
                if (callback != null) {
                    callback.onPagePopFragment();
                }
            }
        }

        @Override
        public void onTabSelected(int position) {
            // move the selected tab to the bottom of the list, and navigate to it!
            // (but only if it's a different tab than the one currently in view!
            if (position != tabList.size() - 1) {
                Tab tab = tabList.remove(position);
                tabList.add(tab);
                tabsProvider.invalidate();
                pageFragmentLoadState.updateCurrentBackStackItem();
                pageFragmentLoadState.setBackStack(tab.getBackStack());
                pageFragmentLoadState.loadFromBackStack();
            }
            tabsProvider.exitTabMode();
            tabFunnel.logSelect(tabList.size(), position);
            leadImagesHandler.setAnimationPaused(false);
        }

        @Override
        public void onNewTabRequested() {
            // just load the main page into a new tab...
            loadMainPageInForegroundTab();
            tabFunnel.logCreateNew(tabList.size());

            // Set the current tab to the new opened tab
            tabsProvider.exitTabMode();
            leadImagesHandler.setAnimationPaused(false);
        }

        @Override
        public void onCloseTabRequested(int position) {
            if (!ReleaseUtil.isDevRelease() && (position < 0 || position >= tabList.size())) {
                // According to T109998, the position may possibly be out-of-bounds, but we can't
                // reproduce it. We'll handle this case, but only for non-dev builds, so that we
                // can investigate the issue further if we happen upon it ourselves.
                return;
            }
            tabList.remove(position);
            tabFunnel.logClose(tabList.size(), position);
            tabsProvider.invalidate();
            getActivity().supportInvalidateOptionsMenu();
            if (tabList.size() == 0) {
                tabFunnel.logCancel(tabList.size());
                tabsProvider.exitTabMode();
                // and if the last tab was closed, then finish the activity!
                if (!tabsProvider.shouldPopFragment()) {
                    getActivity().finish();
                }
            } else if (position == tabList.size()) {
                // if it's the topmost tab, then load the topmost page in the next tab.
                pageFragmentLoadState.setBackStack(getCurrentTab().getBackStack());
                pageFragmentLoadState.loadFromBackStack();
            }
        }

        @Override
        public void onCloseAllTabs() {
            tabList.clear();
            Prefs.clearTabs();
            getActivity().finish();
        }
    };

    @Override
    public void onPause() {
        super.onPause();

        activeTimer.pause();
        addTimeSpentReading(activeTimer.getElapsedSec());

        pageFragmentLoadState.updateCurrentBackStackItem();
        Prefs.setTabs(tabList);
        closePageScrollFunnel();

        long time = tabList.size() >= 1 && !pageFragmentLoadState.backStackEmpty()
                ? System.currentTimeMillis()
                : 0;
        Prefs.pageLastShown(time);
    }

    @Override
    public void onResume() {
        super.onResume();
        initPageScrollFunnel();
        activeTimer.resume();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        sendDecorOffsetMessage();
        // if the screen orientation changes, then re-layout the lead image container,
        // but only if we've finished fetching the page.
        if (!pageFragmentLoadState.isLoading() && !errorState) {
            pageFragmentLoadState.layoutLeadImage();
        }
        tabsProvider.onConfigurationChanged();
    }

    public Tab getCurrentTab() {
        return tabList.get(tabList.size() - 1);
    }

    public void invalidateTabs() {
        tabsProvider.invalidate();
    }

    public void openInNewBackgroundTabFromMenu(@NonNull PageTitle title, @NonNull HistoryEntry entry) {
        if (noPagesOpen()) {
            openInNewForegroundTabFromMenu(title, entry);
        } else {
            openInNewTabFromMenu(title, entry, getBackgroundTabPosition());
            tabsProvider.showAndHideTabs();
        }
    }

    public void openInNewForegroundTabFromMenu(@NonNull PageTitle title, @NonNull HistoryEntry entry) {
        openInNewTabFromMenu(title, entry, getForegroundTabPosition());
        pageFragmentLoadState.loadFromBackStack();
    }

    public void openInNewTabFromMenu(@NonNull PageTitle title, @NonNull HistoryEntry entry, int position) {
        openInNewTab(title, entry, position);
        tabFunnel.logOpenInNew(tabList.size());
    }

    public void loadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry, boolean pushBackStack) {
        //is the new title the same as what's already being displayed?
        if (!getCurrentTab().getBackStack().isEmpty()
                && getCurrentTab().getBackStack().get(getCurrentTab().getBackStack().size() - 1)
                .getTitle().equals(title)) {
            if (model.getPage() == null) {
                pageFragmentLoadState.loadFromBackStack();
            } else if (!TextUtils.isEmpty(title.getFragment())) {
                scrollToSection(title.getFragment());
            }
            return;
        }

        loadPage(title, entry, pushBackStack, 0);
    }

    public void loadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry,
                         boolean pushBackStack, int stagedScrollY) {
        loadPage(title, entry, pushBackStack, stagedScrollY, false);
    }

    public void loadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry,
                         boolean pushBackStack, boolean pageRefreshed) {
        loadPage(title, entry, pushBackStack, 0, pageRefreshed);
    }

    /**
     * Load a new page into the WebView in this fragment.
     * This shall be the single point of entry for loading content into the WebView, whether it's
     * loading an entirely new page, refreshing the current page, retrying a failed network
     * request, etc.
     * @param title Title of the new page to load.
     * @param entry HistoryEntry associated with the new page.
     * @param pushBackStack Whether to push the new page onto the backstack.
     */
    public void loadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry,
                         boolean pushBackStack, int stagedScrollY, boolean pageRefreshed) {
        // update the time spent reading of the current page, before loading the new one
        addTimeSpentReading(activeTimer.getElapsedSec());
        activeTimer.reset();

        // disable sliding of the ToC while sections are loading
        tocHandler.setEnabled(false);

        errorState = false;
        errorView.setVisibility(View.GONE);
        tabLayout.enableAllTabs();

        model.setTitle(title);
        model.setTitleOriginal(title);
        model.setCurEntry(entry);
        model.setReadingListPage(null);

        updateProgressBar(true, true, 0);

        this.pageRefreshed = pageRefreshed;

        closePageScrollFunnel();
        pageFragmentLoadState.load(pushBackStack, stagedScrollY);
        updateBookmark();
    }

    public Bitmap getLeadImageBitmap() {
        return leadImagesHandler.getLeadImageBitmap();
    }

    /**
     * Update the WebView's base font size, based on the specified font size from the app
     * preferences.
     */
    public void updateFontSize() {
        webView.getSettings().setDefaultFontSize((int) app.getFontSize(getActivity().getWindow()));
    }

    public void updateBookmark() {
        if (!isAdded()) {
            return;
        }
        pageActionTabsCallback.updateBookmark(model.isInReadingList());
    }

    public void updateBookmarkFromDao() {
        ReadingList.DAO.anyListContainsTitleAsync(ReadingListDaoProxy.key(getTitle()),
                new CallbackTask.DefaultCallback<ReadingListPage>() {
                    @Override public void success(@Nullable ReadingListPage page) {
                        if (!isAdded()) {
                            return;
                        }
                        model.setReadingListPage(page);
                        pageActionTabsCallback.updateBookmark(page != null);
                    }
                });
    }

    public void onActionModeShown(CompatActionMode mode) {
        // make sure we have a page loaded, since shareHandler makes references to it.
        if (model.getPage() != null) {
            shareHandler.onTextSelected(mode);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.ACTIVITY_REQUEST_EDIT_SECTION
            && resultCode == EditHandler.RESULT_REFRESH_PAGE) {
            pageFragmentLoadState.backFromEditing(data);
            FeedbackUtil.showMessage(getActivity(), R.string.edit_saved_successfully);
            // and reload the page...
            loadPage(model.getTitleOriginal(), model.getCurEntry(), false);
        } else if (requestCode == Constants.ACTIVITY_REQUEST_DESCRIPTION_EDIT_TUTORIAL
                && resultCode == RESULT_OK) {
            PrefsOnboardingStateMachine.getInstance().setDescriptionEditTutorial();
            startDescriptionEditActivity();
        } else if (requestCode == Constants.ACTIVITY_REQUEST_DESCRIPTION_EDIT
                && resultCode == RESULT_OK) {
            refreshPage();
        }
    }

    public void startDescriptionEditActivity() {
        startActivityForResult(DescriptionEditActivity.newIntent(getContext(), getTitle()),
                Constants.ACTIVITY_REQUEST_DESCRIPTION_EDIT);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if (!isAdded()) {
            return;
        }
        if (!isSearching()) {
            inflater.inflate(R.menu.menu_page_actions, menu);
        }
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        if (!isAdded() || isSearching()
                || !(getHostTopFragment() instanceof PageFragment)) {
            return;
        }

        MenuItem otherLangItem = menu.findItem(R.id.menu_page_other_languages);
        MenuItem shareItem = menu.findItem(R.id.menu_page_share);
        MenuItem addToListItem = menu.findItem(R.id.menu_page_add_to_list);
        MenuItem findInPageItem = menu.findItem(R.id.menu_page_find_in_page);
        MenuItem contentIssues = menu.findItem(R.id.menu_page_content_issues);
        MenuItem similarTitles = menu.findItem(R.id.menu_page_similar_titles);
        MenuItem themeChooserItem = menu.findItem(R.id.menu_page_font_and_theme);
        MenuItem tabsItem = menu.findItem(R.id.menu_page_show_tabs);

        tabsItem.setIcon(ResourceUtil.getTabListIcon(tabList.size()));

        if (pageFragmentLoadState.isLoading() || errorState) {
            otherLangItem.setEnabled(false);
            shareItem.setEnabled(false);
            addToListItem.setEnabled(false);
            findInPageItem.setEnabled(false);
            contentIssues.setEnabled(false);
            similarTitles.setEnabled(false);
            themeChooserItem.setEnabled(false);
        } else {
            // Only display "Read in other languages" if the article is in other languages
            otherLangItem.setVisible(model.getPage() != null && model.getPage().getPageProperties().getLanguageCount() != 0);
            otherLangItem.setEnabled(true);
            shareItem.setEnabled(model.getPage() != null && model.getPage().isArticle());
            addToListItem.setEnabled(model.getPage() != null && model.getPage().isArticle());
            findInPageItem.setEnabled(true);
            updateMenuPageInfo(menu);
            themeChooserItem.setEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.homeAsUp:
                // TODO SEARCH: add up navigation, see also http://developer.android.com/training/implementing-navigation/ancestral.html
                return true;
            case R.id.menu_page_other_languages:
                startLangLinksActivity();
                return true;
            case R.id.menu_page_share:
                sharePageLink();
                return true;
            case R.id.menu_page_add_to_list:
                addToReadingList(AddToReadingListDialog.InvokeSource.PAGE_OVERFLOW_MENU);
                return true;
            case R.id.menu_page_find_in_page:
                showFindInPage();
                return true;
            case R.id.menu_page_content_issues:
                showContentIssues();
                return true;
            case R.id.menu_page_similar_titles:
                showSimilarTitles();
                return true;
            case R.id.menu_page_font_and_theme:
                showThemeChooser();
                return true;
            case R.id.menu_page_show_tabs:
                tabsProvider.enterTabMode(false);
                return true;
            case R.id.menu_page_search:
                if (callback() != null) {
                    callback().onPageSearchRequested();
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void sharePageLink() {
        if (getPage() != null) {
            ShareUtil.shareText(getActivity(), getPage().getTitle());
        }
    }

    public void showFindInPage() {
        if (model.getPage() == null) {
            return;
        }
        final FindInPageFunnel funnel = new FindInPageFunnel(app, model.getTitle().getWikiSite(),
                model.getPage().getPageProperties().getPageId());
        final FindInPageActionProvider findInPageActionProvider
                = new FindInPageActionProvider(this, funnel);

        startSupportActionMode(new ActionMode.Callback() {
            private final String actionModeTag = "actionModeFindInPage";

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                findInPageActionMode = mode;
                MenuItem menuItem = menu.add(R.string.menu_page_find_in_page);
                MenuItemCompat.setActionProvider(menuItem, findInPageActionProvider);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                mode.setTag(actionModeTag);
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                findInPageActionMode = null;
                funnel.setPageHeight(webView.getContentHeight());
                funnel.logDone();
                webView.clearMatches();
                showToolbar();
                hideSoftKeyboard();
            }
        });
    }

    public boolean closeFindInPage() {
        if (findInPageActionMode != null) {
            findInPageActionMode.finish();
            return true;
        }
        return false;
    }

    /**
     * Scroll to a specific section in the WebView.
     * @param sectionAnchor Anchor link of the section to scroll to.
     */
    public void scrollToSection(@NonNull String sectionAnchor) {
        if (!isAdded() || tocHandler == null) {
            return;
        }
        tocHandler.scrollToSection(sectionAnchor);
    }

    public void onPageLoadComplete() {
        refreshView.setEnabled(true);
        if (callback() != null) {
            callback().onPageInvalidateOptionsMenu();
        }
        setupToC(model, pageFragmentLoadState.isFirstPage());
        editHandler.setPage(model.getPage());
        initPageScrollFunnel();

        // TODO: update this title in the db to be queued for saving by the service.

        checkAndShowSelectTextOnboarding();

        if (getPageLoadCallbacks() != null) {
            getPageLoadCallbacks().onLoadComplete();
        }
    }

    public void onPageLoadError(@NonNull Throwable caught) {
        if (!isAdded()) {
            return;
        }
        // in any case, make sure the TOC drawer is closed
        tocDrawer.closeDrawers();
        updateProgressBar(false, true, 0);
        refreshView.setRefreshing(false);

        if (pageRefreshed) {
            pageRefreshed = false;
        }

        hidePageContent();
        errorView.setError(caught);
        errorView.setVisibility(View.VISIBLE);

        View contentTopOffset = errorView.findViewById(R.id.view_wiki_error_article_content_top_offset);
        View tabLayoutOffset = errorView.findViewById(R.id.view_wiki_error_article_tab_layout_offset);
        contentTopOffset.setLayoutParams(getContentTopOffsetParams(getContext()));
        contentTopOffset.setVisibility(View.VISIBLE);
        tabLayoutOffset.setLayoutParams(getTabLayoutOffsetParams());
        tabLayoutOffset.setVisibility(View.VISIBLE);

        disableActionTabs(caught);

        refreshView.setEnabled(!ThrowableUtil.is404(caught));
        errorState = true;
        if (callback() != null) {
            callback().onPageLoadError(getTitle());
        }

        if (getPageLoadCallbacks() != null) {
            getPageLoadCallbacks().onLoadError(caught);
        }
    }

    public void refreshPage() {
        if (pageFragmentLoadState.isLoading()) {
            refreshView.setRefreshing(false);
            return;
        }

        errorView.setVisibility(View.GONE);
        tabLayout.enableAllTabs();
        errorState = false;

        model.setCurEntry(new HistoryEntry(model.getTitle(), HistoryEntry.SOURCE_HISTORY));
        loadPage(model.getTitle(), model.getCurEntry(), false, true);
    }

    private ToCHandler tocHandler;
    public void toggleToC(int action) {
        // tocHandler could still be null while the page is loading
        if (tocHandler == null) {
            return;
        }
        switch (action) {
            case TOC_ACTION_SHOW:
                tocHandler.show();
                break;
            case TOC_ACTION_HIDE:
                tocHandler.hide();
                break;
            case TOC_ACTION_TOGGLE:
                if (tocHandler.isVisible()) {
                    tocHandler.hide();
                } else {
                    tocHandler.show();
                }
                break;
            default:
                throw new RuntimeException("Unknown action!");
        }
    }

    private void setupToC(@NonNull PageViewModel model, boolean isFirstPage) {
        tocHandler.setupToC(model.getPage(), model.getTitle().getWikiSite(), isFirstPage);
        tocHandler.setEnabled(true);
    }

    private void updateMenuPageInfo(@NonNull Menu menu) {
        MenuItem contentIssues = menu.findItem(R.id.menu_page_content_issues);
        MenuItem similarTitles = menu.findItem(R.id.menu_page_similar_titles);
        contentIssues.setVisible(pageInfo != null && pageInfo.hasContentIssues());
        contentIssues.setEnabled(true);
        similarTitles.setVisible(pageInfo != null && pageInfo.hasSimilarTitles());
        similarTitles.setEnabled(true);
    }

    private void setBookmarkIconForPageSavedState(boolean pageSaved) {
        TabLayout.Tab bookmarkTab = tabLayout.getTabAt(PageActionTab.ADD_TO_READING_LIST.code());
        if (bookmarkTab != null) {
            bookmarkTab.setIcon(pageSaved ? R.drawable.ic_bookmark_white_24dp
                    : R.drawable.ic_bookmark_border_white_24dp);
        }
    }

    private void showContentIssues() {
        showPageInfoDialog(false);
    }

    private void showSimilarTitles() {
        showPageInfoDialog(true);
    }

    private void showPageInfoDialog(boolean startAtDisambig) {
        showBottomSheet(new PageInfoDialog(this, pageInfo, startAtDisambig));
    }

    private void showTabList() {
        // Doesn't seem to be a way around doing a post() here...
        // Without post(), the tab picker layout is inflated with wrong dimensions.
        webView.post(new Runnable() {
            @Override
            public void run() {
                tabsProvider.enterTabMode(true);
            }
        });
    }

    private void openInNewTab(@NonNull PageTitle title, @NonNull HistoryEntry entry, int position) {
        if (shouldCreateNewTab()) {
            // create a new tab
            Tab tab = new Tab();
            // if the requested position is at the top, then make its backstack current
            if (position == getForegroundTabPosition()) {
                pageFragmentLoadState.setBackStack(tab.getBackStack());
            }
            // put this tab in the requested position
            tabList.add(position, tab);
            trimTabCount();
            tabsProvider.invalidate();
            // add the requested page to its backstack
            tab.getBackStack().add(new PageBackStackItem(title, entry));
            getActivity().supportInvalidateOptionsMenu();
        } else {
            getTopMostTab().getBackStack().add(new PageBackStackItem(title, entry));
        }
    }

    private boolean noPagesOpen() {
        return tabList.isEmpty()
                || (tabList.size() == 1 && tabList.get(0).getBackStack().isEmpty());
    }

    private Tab getTopMostTab() {
        return tabList.get(tabList.size() - 1);
    }

    private boolean shouldCreateNewTab() {
        return !getTopMostTab().getBackStack().isEmpty();
    }

    private int getBackgroundTabPosition() {
        return Math.max(0, getForegroundTabPosition() - 1);
    }

    private int getForegroundTabPosition() {
        return tabList.size();
    }

    private void setupMessageHandlers() {
        linkHandler = new LinkHandler(getActivity()) {
            @Override public void onPageLinkClicked(@NonNull String anchor) {
                dismissBottomSheet();
                JSONObject payload = new JSONObject();
                try {
                    payload.put("anchor", anchor);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                bridge.sendMessage("handleReference", payload);
            }

            @Override public void onInternalLinkClicked(@NonNull PageTitle title) {
                handleInternalLink(title);
            }

            @Override public WikiSite getWikiSite() {
                return model.getTitle().getWikiSite();
            }
        };
        bridge.addListener("linkClicked", linkHandler);

        bridge.addListener("referenceClicked", new ReferenceHandler() {
            @Override
            protected void onReferenceClicked(@NonNull String refHtml, @Nullable String refLinkText) {
                if (!isAdded()) {
                    Log.d("PageFragment", "Detached from activity, so stopping reference click.");
                    return;
                }
                showBottomSheet(new ReferenceDialog(getActivity(), linkHandler, refHtml,
                        StringUtils.defaultString(refLinkText)));
            }
        });
        bridge.addListener("ipaSpan", new CommunicationBridge.JSEventListener() {
            @Override public void onMessage(String messageType, JSONObject messagePayload) {
                try {
                    String text = messagePayload.getString("contents");
                    final int textSize = 30;
                    TextView textView = new TextView(getActivity());
                    textView.setGravity(Gravity.CENTER);
                    textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
                    textView.setText(StringUtil.fromHtml(text));
                    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                    builder.setView(textView);
                    builder.show();
                } catch (JSONException e) {
                    L.logRemoteErrorIfProd(e);
                }
            }
        });
        bridge.addListener("imageClicked", new CommunicationBridge.JSEventListener() {
            @Override public void onMessage(String messageType, JSONObject messagePayload) {
                try {
                    String href = decodeURL(messagePayload.getString("href"));
                    if (href.startsWith("/wiki/")) {
                        String filename = UriUtil.removeInternalLinkPrefix(href);
                        WikiSite wiki = model.getTitle().getWikiSite();
                        getActivity().startActivityForResult(GalleryActivity.newIntent(getActivity(),
                                model.getTitleOriginal(), filename, wiki,
                                GalleryFunnel.SOURCE_NON_LEAD_IMAGE),
                                Constants.ACTIVITY_REQUEST_GALLERY);
                    } else {
                        linkHandler.onUrlClick(href, messagePayload.optString("title"));
                    }
                } catch (JSONException e) {
                    L.logRemoteErrorIfProd(e);
                }
            }
        });
        bridge.addListener("mediaClicked", new CommunicationBridge.JSEventListener() {
            @Override public void onMessage(String messageType, JSONObject messagePayload) {
                try {
                    String href = decodeURL(messagePayload.getString("href"));
                    String filename = StringUtil.removeUnderscores(UriUtil.removeInternalLinkPrefix(href));
                    WikiSite wiki = model.getTitle().getWikiSite();
                    getActivity().startActivityForResult(GalleryActivity.newIntent(getActivity(),
                            model.getTitleOriginal(), filename, wiki,
                            GalleryFunnel.SOURCE_NON_LEAD_IMAGE),
                            Constants.ACTIVITY_REQUEST_GALLERY);
                } catch (JSONException e) {
                    L.logRemoteErrorIfProd(e);
                }
            }
        });
    }

    /**
     * Convenience method for hiding all the content of a page.
     */
    private void hidePageContent() {
        leadImagesHandler.hide();
        toolbarHideHandler.setFadeEnabled(false);
        pageFragmentLoadState.onHidePageContent();
        webView.setVisibility(View.INVISIBLE);
    }

    @Override
    public boolean onBackPressed() {
        if (tocHandler != null && tocHandler.isVisible()) {
            tocHandler.hide();
            return true;
        }
        if (closeFindInPage()) {
            return true;
        }
        if (pageFragmentLoadState.popBackStack()) {
            return true;
        }
        if (tabsProvider.onBackPressed()) {
            return true;
        }
        if (tabList.size() > 1) {
            // if we're at the end of the current tab's backstack, then pop the current tab.
            tabList.remove(tabList.size() - 1);
            tabsProvider.invalidate();
        }
        return false;
    }

    public LinkHandler getLinkHandler() {
        return linkHandler;
    }

    public void updatePageInfo(@Nullable PageInfo pageInfo) {
        this.pageInfo = pageInfo;
        if (getActivity() != null) {
            getActivity().supportInvalidateOptionsMenu();
        }
    }

    private void checkAndShowSelectTextOnboarding() {
        if (model.getPage().isArticle()
                &&  app.getOnboardingStateMachine().isSelectTextTutorialEnabled()) {
            showSelectTextOnboarding();
        }
    }

    private void showSelectTextOnboarding() {
        final View targetView = getView().findViewById(R.id.fragment_page_tool_tip_select_text_target);
        targetView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (getActivity() != null) {
                    ToolTipUtil.showToolTip(getActivity(),
                                            targetView, R.layout.inflate_tool_tip_select_text,
                                            ToolTip.Position.CENTER);
                    app.getOnboardingStateMachine().setSelectTextTutorial();
                }
            }
        }, TimeUnit.SECONDS.toMillis(1));
    }

    private void initTabs() {
        if (Prefs.hasTabs()) {
            tabList.addAll(Prefs.getTabs());
        }

        if (tabList.isEmpty()) {
            tabList.add(new Tab());
        }
    }

    private void sendDecorOffsetMessage() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("offset", getContentTopOffset(getActivity()));
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        bridge.sendMessage("setDecorOffset", payload);
    }

    private void initPageScrollFunnel() {
        if (model.getPage() != null) {
            pageScrollFunnel = new PageScrollFunnel(app, model.getPage().getPageProperties().getPageId());
        }
    }

    private void closePageScrollFunnel() {
        if (pageScrollFunnel != null && webView.getContentHeight() > 0) {
            pageScrollFunnel.setViewportHeight(webView.getHeight());
            pageScrollFunnel.setPageHeight(webView.getContentHeight());
            pageScrollFunnel.logDone();
        }
        pageScrollFunnel = null;
    }

    private class PageFragmentLongPressHandler extends PageContainerLongPressHandler
            implements LongPressHandler.WebViewContextMenuListener {

        PageFragmentLongPressHandler(@NonNull PageFragment.Callback callback) {
            super(callback);
        }

        @Override
        public WikiSite getWikiSite() {
            return model.getTitleOriginal().getWikiSite();
        }
    }

    public void showBottomSheet(@NonNull BottomSheetDialog dialog) {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageShowBottomSheet(dialog);
        }
    }

    public void showBottomSheet(@NonNull BottomSheetDialogFragment dialog) {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageShowBottomSheet(dialog);
        }
    }

    private void dismissBottomSheet() {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageDismissBottomSheet();
        }
    }

    @Nullable
    public PageToolbarHideHandler getSearchBarHideHandler() {
        PageToolbarHideHandler handler = null;
        Callback callback = callback();
        if (callback != null) {
            handler = callback.onPageGetToolbarHideHandler();
        }
        return handler;
    }

    public void loadPage(@NonNull PageTitle title, @NonNull HistoryEntry entry) {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageLoadPage(title, entry);
        }
    }

    private void showLinkPreview(@NonNull PageTitle title, int source) {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageShowLinkPreview(title, source);
        }
    }

    private void loadMainPageInForegroundTab() {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageLoadMainPageInForegroundTab();
        }
    }

    private void updateProgressBar(boolean visible, boolean indeterminate, int value) {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageUpdateProgressBar(visible, indeterminate, value);
        }
    }

    private boolean isSearching() {
        boolean isSearching = false;
        Callback callback = callback();
        if (callback != null) {
            isSearching = callback.onPageIsSearching();
        }
        return isSearching;
    }

    @Nullable
    private Fragment getHostTopFragment() {
        Fragment fragment = null;
        Callback callback = callback();
        if (callback != null) {
            fragment = callback.onPageGetTopFragment();
        }
        return fragment;
    }

    private void showThemeChooser() {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageShowThemeChooser();
        }
    }

    @Nullable
    public ActionMode startSupportActionMode(@NonNull ActionMode.Callback actionModeCallback) {
        ActionMode actionMode = null;
        Callback callback = callback();
        if (callback != null) {
            actionMode = callback.onPageStartSupportActionMode(actionModeCallback);
        }
        return actionMode;
    }

    public void showToolbar() {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageShowToolbar();
        }
    }

    public void hideSoftKeyboard() {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageHideSoftKeyboard();
        }
    }

    @Nullable
    private PageLoadCallbacks getPageLoadCallbacks() {
        PageLoadCallbacks callbacks = null;
        Callback callback = callback();
        if (callback != null) {
            callbacks = callback.onPageGetPageLoadCallbacks();
        }
        return callbacks;
    }

    public void addToReadingList(@NonNull AddToReadingListDialog.InvokeSource source) {
        Callback callback = callback();
        if (callback != null) {
            callback.onPageAddToReadingList(getTitle(), source);
        }
    }

    @Nullable
    public View getContentView() {
        View view = null;
        Callback callback = callback();
        if (callback != null) {
            view = callback.onPageGetContentView();
        }
        return view;
    }

    @Nullable
    public View getTabsContainerView() {
        View view = null;
        Callback callback = callback();
        if (callback != null) {
            view = callback.onPageGetTabsContainerView();
        }
        return view;
    }

    private void startLangLinksActivity() {
        Intent langIntent = new Intent();
        langIntent.setClass(getActivity(), LangLinksActivity.class);
        langIntent.setAction(LangLinksActivity.ACTION_LANGLINKS_FOR_TITLE);
        langIntent.putExtra(LangLinksActivity.EXTRA_PAGETITLE, model.getTitle());
        getActivity().startActivityForResult(langIntent, Constants.ACTIVITY_REQUEST_LANGLINKS);
    }

    private void trimTabCount() {
        while (tabList.size() > Constants.MAX_TABS) {
            tabList.remove(0);
        }
    }

    private void addTimeSpentReading(int timeSpentSec) {
        if (model.getCurEntry() == null) {
            return;
        }
        model.setCurEntry(new HistoryEntry(model.getCurEntry().getTitle(),
                new Date(),
                model.getCurEntry().getSource(),
                timeSpentSec));
        new UpdateHistoryTask(model.getCurEntry(), app).execute();
    }

    private LinearLayout.LayoutParams getContentTopOffsetParams(@NonNull Context context) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, getContentTopOffsetPx(context));
    }

    private LinearLayout.LayoutParams getTabLayoutOffsetParams() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, tabLayout.getHeight());
    }

    private void disableActionTabs(@Nullable Throwable caught) {
        boolean offline = caught != null && isOffline(caught);
        for (int i = 0; i < tabLayout.getTabCount(); i++) {
            if (!(offline && PageActionTab.of(i).equals(PageActionTab.ADD_TO_READING_LIST))) {
                tabLayout.disableTab(i);
            }
        }
    }

    @Nullable
    public Callback callback() {
        return FragmentUtil.getCallback(this, Callback.class);
    }
}
