# Exact target APIs referenced by instrumentation bytecode and its test-only libraries.
# R8 still optimizes these methods and obfuscates them using AGP's test mapping.
# Regenerate after adding test calls; this distribution intentionally retains this test API.
-keep,allowoptimization,allowobfuscation class androidx.activity.ComponentActivity {
    void addContentView(android.view.View,android.view.ViewGroup$LayoutParams);
    androidx.lifecycle.ViewModelStore getViewModelStore();
}
-keep,allowoptimization,allowobfuscation class androidx.activity.compose.ComponentActivityKt {
    void setContent$default(androidx.activity.ComponentActivity,androidx.compose.runtime.CompositionContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.activity.compose.LocalActivityKt {
    androidx.compose.runtime.ProvidableCompositionLocal getLocalActivity();
}
-keep,allowoptimization,allowobfuscation class androidx.activity.compose.LocalActivityResultRegistryOwner {
    androidx.activity.compose.LocalActivityResultRegistryOwner INSTANCE;
    androidx.compose.runtime.ProvidedValue provides(androidx.activity.result.ActivityResultRegistryOwner);
}
-keep,allowoptimization,allowobfuscation class androidx.activity.result.ActivityResultRegistryOwner {
}
-keep,allowoptimization,allowobfuscation class androidx.annotation.RestrictTo {
}
-keep,allowoptimization,allowobfuscation class androidx.annotation.RestrictTo$Scope {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.BackgroundKt {
    androidx.compose.ui.Modifier background-bw27NRU$default(androidx.compose.ui.Modifier,long,androidx.compose.ui.graphics.Shape,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.ScrollKt {
    androidx.compose.foundation.ScrollState rememberScrollState(int,androidx.compose.runtime.Composer,int,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.ScrollState {
    <init>(int);
    float dispatchRawDelta(float);
    int getMaxValue();
    int getValue();
    int getViewportSize();
    boolean isScrollInProgress();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.Arrangement {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.Arrangement$Vertical {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.BoxKt {
    androidx.compose.ui.layout.MeasurePolicy maybeCachedBoxMeasurePolicy(androidx.compose.ui.Alignment,boolean);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.BoxScope {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.BoxScopeInstance {
    androidx.compose.foundation.layout.BoxScopeInstance INSTANCE;
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.SizeKt {
    androidx.compose.ui.Modifier fillMaxSize$default(androidx.compose.ui.Modifier,float,int,java.lang.Object);
    androidx.compose.ui.Modifier fillMaxWidth$default(androidx.compose.ui.Modifier,float,int,java.lang.Object);
    androidx.compose.ui.Modifier height-3ABfNKs(androidx.compose.ui.Modifier,float);
    androidx.compose.ui.Modifier width-3ABfNKs(androidx.compose.ui.Modifier,float);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.layout.WindowInsetsPadding_androidKt {
    androidx.compose.ui.Modifier safeDrawingPadding(androidx.compose.ui.Modifier);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyDslKt {
    void LazyColumn(androidx.compose.ui.Modifier,androidx.compose.foundation.lazy.LazyListState,androidx.compose.foundation.layout.PaddingValues,boolean,androidx.compose.foundation.layout.Arrangement$Vertical,androidx.compose.ui.Alignment$Horizontal,androidx.compose.foundation.gestures.FlingBehavior,boolean,androidx.compose.foundation.OverscrollEffect,kotlin.jvm.functions.Function1,androidx.compose.runtime.Composer,int,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyItemScope {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyListItemInfo {
    int getSize();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyListLayoutInfo {
    int getViewportEndOffset();
    int getViewportStartOffset();
    java.util.List getVisibleItemsInfo();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyListScope {
    void items$default(androidx.compose.foundation.lazy.LazyListScope,int,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function4,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyListState {
    int getFirstVisibleItemIndex();
    int getFirstVisibleItemScrollOffset();
    androidx.compose.foundation.lazy.LazyListLayoutInfo getLayoutInfo();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.foundation.lazy.LazyListStateKt {
    androidx.compose.foundation.lazy.LazyListState rememberLazyListState(int,int,androidx.compose.runtime.Composer,int,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.material3.ColorScheme {
    long getPrimary-0d7_KjU();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.material3.DynamicTonalPaletteKt {
    androidx.compose.material3.ColorScheme dynamicDarkColorScheme(android.content.Context);
    androidx.compose.material3.ColorScheme dynamicLightColorScheme(android.content.Context);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.material3.MaterialTheme {
    int $stable;
    androidx.compose.material3.MaterialTheme INSTANCE;
    androidx.compose.material3.Typography getTypography(androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.material3.TextKt {
    void Text-Nvy7gAk(java.lang.String,androidx.compose.ui.Modifier,long,androidx.compose.foundation.text.TextAutoSize,long,androidx.compose.ui.text.font.FontStyle,androidx.compose.ui.text.font.FontWeight,androidx.compose.ui.text.font.FontFamily,long,androidx.compose.ui.text.style.TextDecoration,androidx.compose.ui.text.style.TextAlign,long,int,boolean,int,int,kotlin.jvm.functions.Function1,androidx.compose.ui.text.TextStyle,androidx.compose.runtime.Composer,int,int,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.material3.Typography {
    androidx.compose.ui.text.TextStyle getBodyLarge();
    androidx.compose.ui.text.TextStyle getBodyMedium();
    androidx.compose.ui.text.TextStyle getBodySmall();
    androidx.compose.ui.text.TextStyle getDisplayLarge();
    androidx.compose.ui.text.TextStyle getDisplayMedium();
    androidx.compose.ui.text.TextStyle getDisplaySmall();
    androidx.compose.ui.text.TextStyle getHeadlineLarge();
    androidx.compose.ui.text.TextStyle getHeadlineMedium();
    androidx.compose.ui.text.TextStyle getHeadlineSmall();
    androidx.compose.ui.text.TextStyle getLabelLarge();
    androidx.compose.ui.text.TextStyle getLabelMedium();
    androidx.compose.ui.text.TextStyle getLabelSmall();
    androidx.compose.ui.text.TextStyle getTitleLarge();
    androidx.compose.ui.text.TextStyle getTitleMedium();
    androidx.compose.ui.text.TextStyle getTitleSmall();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.Applier {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.ComposablesKt {
    long getCurrentCompositeKeyHashCode(androidx.compose.runtime.Composer,int);
    void invalidApplier();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.Composer {
    androidx.compose.runtime.Composer$Companion Companion;
    void apply(java.lang.Object,kotlin.jvm.functions.Function2);
    boolean changed(int);
    boolean changed(long);
    boolean changed(java.lang.Object);
    boolean changedInstance(java.lang.Object);
    java.lang.Object consume(androidx.compose.runtime.CompositionLocal);
    void createNode(kotlin.jvm.functions.Function0);
    void endNode();
    void endReplaceGroup();
    androidx.compose.runtime.Applier getApplier();
    androidx.compose.runtime.CompositionLocalMap getCurrentCompositionLocalMap();
    boolean getInserting();
    java.lang.Object rememberedValue();
    boolean shouldExecute(boolean,int);
    void skipToGroupEnd();
    void startReplaceGroup(int);
    void startReusableNode();
    void updateRememberedValue(java.lang.Object);
    void useNode();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.Composer$Companion {
    java.lang.Object getEmpty();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.ComposerKt {
    boolean isTraceInProgress();
    void sourceInformation(androidx.compose.runtime.Composer,java.lang.String);
    void sourceInformationMarkerEnd(androidx.compose.runtime.Composer);
    void sourceInformationMarkerStart(androidx.compose.runtime.Composer,int,java.lang.String);
    void traceEventEnd();
    void traceEventStart(int,int,int,java.lang.String);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.CompositionLocal {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.CompositionLocalKt {
    void CompositionLocalProvider(androidx.compose.runtime.ProvidedValue,kotlin.jvm.functions.Function2,androidx.compose.runtime.Composer,int);
    void CompositionLocalProvider(androidx.compose.runtime.ProvidedValue[],kotlin.jvm.functions.Function2,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.CompositionLocalMap {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.DisposableEffectResult {
    void dispose();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.DisposableEffectScope {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.EffectsKt {
    void DisposableEffect(java.lang.Object,kotlin.jvm.functions.Function1,androidx.compose.runtime.Composer,int);
    void LaunchedEffect(java.lang.Object,kotlin.jvm.functions.Function2,androidx.compose.runtime.Composer,int);
    void SideEffect(kotlin.jvm.functions.Function0,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.IntState {
    int getIntValue();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.LongState {
    long getLongValue();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.MonotonicFrameClockKt {
    java.lang.Object withFrameNanos(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.MutableIntState {
    int getIntValue();
    void setIntValue(int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.MutableLongState {
    void setLongValue(long);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.MutableState {
    java.lang.Object getValue();
    void setValue(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.ProvidableCompositionLocal {
    androidx.compose.runtime.ProvidedValue provides(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.ProvidedValue {
    int $stable;
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.SnapshotIntStateKt {
    androidx.compose.runtime.MutableIntState mutableIntStateOf(int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.SnapshotLongStateKt {
    androidx.compose.runtime.MutableLongState mutableLongStateOf(long);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.SnapshotStateKt {
    androidx.compose.runtime.MutableState mutableStateOf$default(java.lang.Object,androidx.compose.runtime.SnapshotMutationPolicy,int,java.lang.Object);
    kotlinx.coroutines.flow.Flow snapshotFlow(kotlin.jvm.functions.Function0);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.State {
    java.lang.Object getValue();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.Updater {
    androidx.compose.runtime.Composer constructor-impl(androidx.compose.runtime.Composer);
    void set-impl(androidx.compose.runtime.Composer,java.lang.Object,kotlin.jvm.functions.Function2);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.internal.ComposableLambdaKt {
    androidx.compose.runtime.internal.ComposableLambda composableLambdaInstance(int,boolean,java.lang.Object);
    androidx.compose.runtime.internal.ComposableLambda rememberComposableLambda(int,boolean,java.lang.Object,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.runtime.snapshots.SnapshotStateList {
    boolean contains(java.lang.Object);
    boolean isEmpty();
    java.util.List toList();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.Alignment {
    androidx.compose.ui.Alignment$Companion Companion;
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.Alignment$Companion {
    androidx.compose.ui.Alignment getTopStart();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.Alignment$Horizontal {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.ComposedModifierKt {
    androidx.compose.ui.Modifier materializeModifier(androidx.compose.runtime.Composer,androidx.compose.ui.Modifier);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.Modifier {
    androidx.compose.ui.Modifier$Companion Companion;
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.Modifier$Companion {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.geometry.Rect {
    long getCenter-F1C5BW0();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.graphics.ColorKt {
    int toArgb-8_81llA(long);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.layout.MeasurePolicy {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.layout.OnRemeasuredModifierKt {
    androidx.compose.ui.Modifier onSizeChanged(androidx.compose.ui.Modifier,kotlin.jvm.functions.Function1);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.node.ComposeUiNode {
    androidx.compose.ui.node.ComposeUiNode$Companion Companion;
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.node.ComposeUiNode$Companion {
    kotlin.jvm.functions.Function0 getConstructor();
    kotlin.jvm.functions.Function2 getSetCompositeKeyHash();
    kotlin.jvm.functions.Function2 getSetMeasurePolicy();
    kotlin.jvm.functions.Function2 getSetModifier();
    kotlin.jvm.functions.Function2 getSetResolvedCompositionLocals();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    androidx.compose.runtime.ProvidableCompositionLocal getLocalConfiguration();
    androidx.compose.runtime.ProvidableCompositionLocal getLocalContext();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.platform.CompositionLocalsKt {
    androidx.compose.runtime.ProvidableCompositionLocal getLocalDensity();
    androidx.compose.runtime.ProvidableCompositionLocal getLocalLayoutDirection();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.platform.TestTagKt {
    androidx.compose.ui.Modifier testTag(androidx.compose.ui.Modifier,java.lang.String);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.semantics.SemanticsModifierKt {
    androidx.compose.ui.Modifier semantics$default(androidx.compose.ui.Modifier,boolean,kotlin.jvm.functions.Function1,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.semantics.SemanticsProperties_androidKt {
    void setTestTagsAsResourceId(androidx.compose.ui.semantics.SemanticsPropertyReceiver,boolean);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.TextLayoutResult {
    androidx.compose.ui.geometry.Rect getBoundingBox(int);
    androidx.compose.ui.text.style.ResolvedTextDirection getParagraphDirection(int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.TextMeasurer {
    androidx.compose.ui.text.TextLayoutResult measure-wNUYSr0$default(androidx.compose.ui.text.TextMeasurer,java.lang.String,androidx.compose.ui.text.TextStyle,int,boolean,int,long,androidx.compose.ui.unit.LayoutDirection,androidx.compose.ui.unit.Density,androidx.compose.ui.text.font.FontFamily$Resolver,boolean,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.TextMeasurerHelperKt {
    androidx.compose.ui.text.TextMeasurer rememberTextMeasurer(int,androidx.compose.runtime.Composer,int,int);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.TextStyle {
    long getLineHeight-XSAIIZE();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.font.FontFamily {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.font.FontFamily$Resolver {
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.text.style.ResolvedTextDirection {
    androidx.compose.ui.text.style.ResolvedTextDirection Ltr;
    androidx.compose.ui.text.style.ResolvedTextDirection Rtl;
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.ConstraintsKt {
    long Constraints$default(int,int,int,int,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.Density {
    float toPx-0680j_4(float);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.DensityKt {
    androidx.compose.ui.unit.Density Density(float,float);
    androidx.compose.ui.unit.Density Density$default(float,float,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.Dp {
    androidx.compose.ui.unit.Dp box-impl(float);
    float constructor-impl(float);
    float unbox-impl();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.FontScaling {
    float toDp-GaN1DYA(long);
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.IntSize {
    long unbox-impl();
}
-keep,allowoptimization,allowobfuscation class androidx.compose.ui.unit.LayoutDirection {
    androidx.compose.ui.unit.LayoutDirection Ltr;
    androidx.compose.ui.unit.LayoutDirection Rtl;
}
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.AbstractResolvableFuture {
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
    boolean cancel(boolean);
    java.lang.Object get();
    java.lang.Object get(long,java.util.concurrent.TimeUnit);
    java.lang.Object getUninterruptibly(java.util.concurrent.Future);
    boolean isCancelled();
    boolean isDone();
}
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.CallbackToFutureAdapter {
    com.google.common.util.concurrent.ListenableFuture getFuture(androidx.concurrent.futures.CallbackToFutureAdapter$Resolver);
}
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.CallbackToFutureAdapter$Completer {
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
}
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.CallbackToFutureAdapter$Resolver {
    java.lang.Object attachCompleter(androidx.concurrent.futures.CallbackToFutureAdapter$Completer);
}
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.DirectExecutor {
    androidx.concurrent.futures.DirectExecutor INSTANCE;
}
-keep,allowoptimization,allowobfuscation class androidx.concurrent.futures.ResolvableFuture {
    androidx.concurrent.futures.ResolvableFuture create();
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
}
-keep,allowoptimization,allowobfuscation class androidx.core.app.NotificationManagerCompat {
    boolean areNotificationsEnabled();
    androidx.core.app.NotificationManagerCompat from(android.content.Context);
}
-keep,allowoptimization,allowobfuscation class androidx.core.content.ContextCompat {
    android.graphics.drawable.Drawable getDrawable(android.content.Context,int);
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.Lifecycle {
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.Lifecycle$State {
    androidx.lifecycle.Lifecycle$State CREATED;
    androidx.lifecycle.Lifecycle$State DESTROYED;
    androidx.lifecycle.Lifecycle$State RESUMED;
    androidx.lifecycle.Lifecycle$State STARTED;
    androidx.lifecycle.Lifecycle$State[] values();
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.ViewModelProvider {
    <init>(androidx.lifecycle.ViewModelStore,androidx.lifecycle.ViewModelProvider$Factory,androidx.lifecycle.viewmodel.CreationExtras,int,kotlin.jvm.internal.DefaultConstructorMarker);
    <init>(androidx.lifecycle.ViewModelStoreOwner);
    <init>(androidx.lifecycle.ViewModelStoreOwner,androidx.lifecycle.ViewModelProvider$Factory);
    androidx.lifecycle.ViewModel get(java.lang.Class);
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory {
    androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory$Companion Companion;
    <init>(android.app.Application);
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory$Companion {
    androidx.lifecycle.ViewModelProvider$AndroidViewModelFactory getInstance(android.app.Application);
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.ViewModelProvider$Factory {
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.ViewModelStore {
    <init>();
    void clear();
}
-keep,allowoptimization,allowobfuscation class androidx.lifecycle.ViewModelStoreOwner {
}
-keep,allowoptimization,allowobfuscation class androidx.tracing.Trace {
    void beginSection(java.lang.String);
    void endSection();
    void forceEnableAppTracing();
}
-keep,allowoptimization,allowobfuscation class com.google.common.util.concurrent.ListenableFuture {
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.LauncherActivity {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.MainActivity {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.R {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.R$drawable {
    int ic_launcher_foreground;
    int ic_launcher_foreground_dark;
    int ic_launcher_foreground_neutral;
    int ic_launcher_monochrome;
    int ic_notification;
    int sunlith_mark;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.R$id {
    int widget_background;
    int widget_day;
    int widget_divider;
    int widget_events;
    int widget_meta;
    int widget_row;
    int widget_time;
    int widget_title;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.R$layout {
    int widget_event;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.R$plurals {
    int calendars_shown;
    int event_count;
    int school_disconnect_remove;
    int school_sync_changes_count;
    int timeline_short_event_count;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.R$string {
    int about_source;
    int action_allow_notifications;
    int action_back;
    int action_cancel;
    int action_close;
    int action_delete;
    int action_done;
    int action_edit;
    int action_new_event;
    int action_next_day;
    int action_next_month;
    int action_next_week;
    int action_prev_day;
    int action_prev_month;
    int action_prev_week;
    int action_save;
    int action_search;
    int action_set_up_ai;
    int action_today;
    int action_without_ai;
    int ai_intro_title;
    int all_day;
    int app_name;
    int beta_collapse_calendar;
    int beta_settings_about;
    int beta_settings_appearance;
    int beta_settings_transfer;
    int beta_settings_widget;
    int beta_start_view;
    int beta_widget_calendar_name;
    int beta_widget_days;
    int beta_widget_empty_days;
    int calendar_next_five_days;
    int calendar_prev_five_days;
    int calendar_teacher_absent;
    int calendar_teacher_absent_range;
    int connection_setup_connect_computer;
    int connection_setup_open_calendar;
    int connection_setup_share_again;
    int custom_computer_access_desc;
    int custom_schedule;
    int delete_event_title;
    int details_school_periods;
    int details_teacher_absence;
    int device_link_consent;
    int device_link_details;
    int device_link_history;
    int device_link_history_added;
    int device_link_history_clear;
    int device_link_history_clear_body;
    int device_link_last_request;
    int device_link_no_contact;
    int device_link_notification_title;
    int device_link_on;
    int device_link_paired_since;
    int device_link_start;
    int device_link_stop;
    int device_link_title;
    int device_link_unpair;
    int device_link_unpair_body;
    int editor_edit;
    int editor_new;
    int handoff_waiting;
    int import_destination;
    int launcher_icon_apply;
    int launcher_icon_choice;
    int launcher_icon_neutral;
    int launcher_icon_notice_title;
    int layout_continue;
    int layout_preset_agenda;
    int layout_preset_grid;
    int layout_preset_schedule;
    int layout_setup_title;
    int no_events;
    int ongoing;
    int school_alert_added;
    int school_alert_assessments;
    int school_alert_class_times;
    int school_alert_connection;
    int school_alert_removed;
    int school_alert_rooms;
    int school_alert_school_days;
    int school_alert_teacher_absences;
    int school_alert_teachers;
    int school_apply;
    int school_change_connection;
    int school_choose_institution;
    int school_connect;
    int school_disconnect;
    int school_disconnect_remove_confirm;
    int school_disconnect_title;
    int school_error_calendar_permission;
    int school_error_existing_classes;
    int school_error_sign_in;
    int school_link_title;
    int school_montmorency;
    int school_more_options;
    int school_not_now;
    int school_notifications;
    int school_notifications_blocked;
    int school_open_android_settings;
    int school_other_colnet;
    int school_password;
    int school_portal;
    int school_portal_help;
    int school_portal_short;
    int school_sign_in_again;
    int school_sync_absent_title;
    int school_sync_connection_notification;
    int school_sync_now;
    int school_sync_review_notification;
    int school_sync_sign_in_notification;
    int school_sync_teacher_absent;
    int school_title;
    int school_username;
    int search_none;
    int settings_agenda_days;
    int settings_agenda_range;
    int settings_day_all_hours;
    int settings_day_events_only;
    int settings_day_view;
    int settings_export;
    int settings_general;
    int settings_group_empty_hours;
    int settings_import;
    int settings_keep_date;
    int settings_reminders_toggle;
    int settings_theme;
    int settings_week_continuous;
    int settings_widget_opacity;
    int settings_widget_shared;
    int theme_dark;
    int theme_system;
    int title_calendars;
    int title_settings;
    int today;
    int tomorrow;
    int view_day;
    int view_month;
    int view_week;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.CalendarDates {
    io.github.sunsind.sunlith.data.CalendarDates INSTANCE;
    java.time.LocalDate allDayDate(long);
    long allDayMillis(java.time.LocalDate);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.CalendarEvent {
    <init>(long,long,java.lang.String,java.lang.String,java.lang.String,long,long,boolean,java.lang.String,java.lang.String,int,java.lang.String,io.github.sunsind.sunlith.data.SchoolEventInfo,int,kotlin.jvm.internal.DefaultConstructorMarker);
    boolean getAllDay();
    int getColor();
    java.lang.String getDescription();
    long getEndMillis();
    long getId();
    java.lang.String getLocation();
    io.github.sunsind.sunlith.data.SchoolEventInfo getSchool();
    long getStartMillis();
    java.lang.String getTitle();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.CalendarRepository {
    <init>(android.content.Context);
    java.util.List calendars();
    long createLocalCalendar();
    void deleteEvent(long);
    void deleteIfUnchanged$app_distribution(long,java.util.Map);
    io.github.sunsind.sunlith.data.EditBlockReason editBlockReason(long);
    java.util.List events(long,long,java.util.Set,boolean);
    java.util.List events$default(io.github.sunsind.sunlith.data.CalendarRepository,long,long,java.util.Set,boolean,int,java.lang.Object);
    io.github.sunsind.sunlith.data.EventDraft getEvent(long);
    java.util.Map revision$app_distribution(long);
    long saveEvent(io.github.sunsind.sunlith.data.EventDraft);
    java.util.List storedEvents$app_distribution(java.util.Set);
    long updateIfUnchanged$app_distribution(io.github.sunsind.sunlith.data.EventDraft,java.util.Map);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.DeviceCalendar {
    java.lang.String getAccount();
    long getId();
    boolean getLocal();
    boolean getVisible();
    boolean getWritable();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.EditBlockReason {
    io.github.sunsind.sunlith.data.EditBlockReason SCHOOL_MANAGED;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.EventDraft {
    <init>(java.lang.Long,long,java.lang.String,java.lang.String,java.lang.String,long,long,boolean,java.lang.String,java.lang.String,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
    io.github.sunsind.sunlith.data.EventDraft copy$default(io.github.sunsind.sunlith.data.EventDraft,java.lang.Long,long,java.lang.String,java.lang.String,java.lang.String,long,long,boolean,java.lang.String,java.lang.String,java.util.List,int,java.lang.Object);
    boolean getAllDay();
    java.lang.String getDescription();
    long getEndMillis();
    java.lang.Long getId();
    java.lang.String getLocation();
    java.util.List getReminderMinutes();
    long getStartMillis();
    java.lang.String getTimeZone();
    java.lang.String getTitle();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.IcsTransfer {
    <init>(android.content.Context,io.github.sunsind.sunlith.data.CalendarRepository);
    int export(java.io.OutputStream,java.util.Set);
    io.github.sunsind.sunlith.data.ImportReport import(java.io.InputStream,long);
    io.github.sunsind.sunlith.data.ImportPreview inspect(java.io.InputStream);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.ImportPreview {
    int getImportable();
    int getSkipped();
    int getTotal();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.ImportReport {
    int getDuplicates();
    int getImported();
    int getSkipped();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.SchoolAbsenceCoverage {
    io.github.sunsind.sunlith.data.SchoolAbsenceCoverage PARTIAL;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.SchoolEventInfo {
    io.github.sunsind.sunlith.data.SchoolAbsenceCoverage getAbsenceCoverage();
    java.lang.String getCourse();
    java.lang.String getCourseKey();
    java.util.List getPeriods();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.SchoolEventPeriod {
    long getEndMillis();
    long getEventId();
    java.lang.String getLocation();
    long getStartMillis();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.SchoolEventProjectionKt {
    java.util.List projectSchoolEvents(java.util.List,java.time.ZoneId);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.data.StoredEvent {
    io.github.sunsind.sunlith.data.EventDraft getDraft();
    java.lang.String getUid();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.Args {
    <init>(java.util.Map,java.util.Set);
    void confirm();
    io.github.sunsind.sunlith.device.EventTime eventTime(java.lang.String);
    long id(java.lang.String);
    int pageToken();
    java.lang.String recurrence();
    java.util.List reminders();
    boolean seriesScope();
    java.lang.String title();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLink {
    io.github.sunsind.sunlith.device.DeviceLink INSTANCE;
    java.util.List addresses(android.content.Context);
    java.io.File cachedPairingShare(android.content.Context);
    void clearHistory(android.content.Context);
    java.lang.String createPairing(android.content.Context,java.util.Set,java.lang.String);
    io.github.sunsind.sunlith.device.DeviceLinkDiagnostics diagnostics(android.content.Context);
    kotlinx.coroutines.flow.StateFlow getActivity();
    kotlinx.coroutines.flow.StateFlow getState();
    java.util.List history(android.content.Context);
    boolean isPaired(android.content.Context);
    java.util.Set pairedCalendarIds(android.content.Context);
    void revoke(android.content.Context);
    void start(android.content.Context);
    void stop(android.content.Context);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkChange {
    <init>(io.github.sunsind.sunlith.device.DeviceLinkChangeAction,long,long,java.lang.Long,java.lang.String,java.util.List,int,int,int,int,kotlin.jvm.internal.DefaultConstructorMarker);
    io.github.sunsind.sunlith.device.DeviceLinkChangeAction getAction();
    long getAtMillis();
    long getCalendarId();
    java.lang.Long getEventId();
    java.util.List getFields();
    java.lang.String getTitle();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkChangeAction {
    io.github.sunsind.sunlith.device.DeviceLinkChangeAction CREATE;
    io.github.sunsind.sunlith.device.DeviceLinkChangeAction DELETE;
    io.github.sunsind.sunlith.device.DeviceLinkChangeAction IMPORT;
    io.github.sunsind.sunlith.device.DeviceLinkChangeAction UPDATE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkChangeField {
    io.github.sunsind.sunlith.device.DeviceLinkChangeField LOCATION;
    io.github.sunsind.sunlith.device.DeviceLinkChangeField TIME;
    io.github.sunsind.sunlith.device.DeviceLinkChangeField TITLE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkDiagnostics {
    <init>(java.lang.Long,java.lang.Long);
    java.lang.Long getLastSuccessMillis();
    java.lang.Long getPairedAtMillis();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkError {
    java.lang.String getCode();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkHistory {
    io.github.sunsind.sunlith.device.DeviceLinkHistory INSTANCE;
    java.lang.String cleanTitle$app_distribution(java.lang.String);
    void dropCache$app_distribution();
    void record(android.content.Context,io.github.sunsind.sunlith.device.DeviceLinkChange);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkHttp {
    io.github.sunsind.sunlith.device.DeviceLinkHttp INSTANCE;
    io.github.sunsind.sunlith.device.HttpHead parseHead(java.lang.String);
    io.github.sunsind.sunlith.device.HttpHead readHead(java.io.InputStream);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkJson {
    io.github.sunsind.sunlith.device.DeviceLinkJson INSTANCE;
    java.lang.Object parse(byte[]);
    io.github.sunsind.sunlith.device.ToolCall parseCall(byte[]);
    byte[] write(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkNetwork {
    io.github.sunsind.sunlith.device.DeviceLinkNetwork INSTANCE;
    boolean isPrivate(java.net.InetAddress);
    boolean isTailscale(java.net.InetAddress);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkPairing {
    java.util.Set getCalendarIds();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkRateLimiter {
    <init>(kotlin.jvm.functions.Function0);
    boolean admitted(java.lang.String);
    void failure(java.lang.String);
    boolean request(java.lang.String);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkServer {
    <init>(android.content.Context,int,io.github.sunsind.sunlith.device.DeviceLinkTools,io.github.sunsind.sunlith.device.DeviceLinkRateLimiter,int,kotlin.jvm.internal.DefaultConstructorMarker);
    int getLocalPort();
    void start();
    void stop();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkState {
    java.lang.String getError();
    boolean getRunning();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkStore {
    io.github.sunsind.sunlith.device.DeviceLinkStore INSTANCE;
    io.github.sunsind.sunlith.device.DeviceLinkPairing authorize(android.content.Context,java.lang.String);
    void clear(android.content.Context);
    void dropCache$app_distribution();
    java.io.File file$app_distribution(android.content.Context,java.lang.String);
    java.lang.String hex$app_distribution(byte[]);
    java.lang.String pair(android.content.Context,java.util.Set);
    io.github.sunsind.sunlith.device.DeviceLinkPairing pairing(android.content.Context);
    boolean recordSuccess$default(io.github.sunsind.sunlith.device.DeviceLinkStore,android.content.Context,io.github.sunsind.sunlith.device.DeviceLinkPairing,long,int,java.lang.Object);
    byte[] sha256$app_distribution(byte[]);
    void write$app_distribution(java.io.File,byte[]);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkTls {
    io.github.sunsind.sunlith.device.DeviceLinkTls INSTANCE;
    java.lang.String fingerprint();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.DeviceLinkTools {
    <init>(android.content.Context,io.github.sunsind.sunlith.data.CalendarRepository);
    <init>(android.content.Context,io.github.sunsind.sunlith.data.CalendarRepository,int,kotlin.jvm.internal.DefaultConstructorMarker);
    java.util.Map call(java.lang.String,java.util.Map,java.util.Set);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.EventTime {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.EventTime$Timed {
    java.time.Instant getInstant();
    java.time.ZoneId getZone();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.HttpHead {
    java.lang.String bearerToken();
    int contentLength();
    java.lang.String getMethod();
    java.lang.String getTarget();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.device.ToolCall {
    java.util.Map getArguments();
    java.lang.String getTool();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolAlert {
    io.github.sunsind.sunlith.school.SchoolAlert ADDED_CLASSES;
    io.github.sunsind.sunlith.school.SchoolAlert ASSESSMENTS;
    io.github.sunsind.sunlith.school.SchoolAlert CLASS_TIMES;
    io.github.sunsind.sunlith.school.SchoolAlert CONNECTION_PROBLEMS;
    io.github.sunsind.sunlith.school.SchoolAlert REMOVED_CLASSES;
    io.github.sunsind.sunlith.school.SchoolAlert ROOMS;
    io.github.sunsind.sunlith.school.SchoolAlert SCHOOL_DAYS;
    io.github.sunsind.sunlith.school.SchoolAlert TEACHERS;
    io.github.sunsind.sunlith.school.SchoolAlert TEACHER_ABSENCES;
    kotlin.enums.EnumEntries getEntries();
    io.github.sunsind.sunlith.school.SchoolAlert[] values();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolCalendarChoice {
    <init>(long,java.lang.String);
    long getId();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolCalendarWriter {
    io.github.sunsind.sunlith.school.SchoolCalendarWriter$Companion Companion;
    <init>(android.content.Context,kotlin.jvm.functions.Function1);
    <init>(android.content.Context,kotlin.jvm.functions.Function1,int,kotlin.jvm.internal.DefaultConstructorMarker);
    boolean baselineUnchanged(io.github.sunsind.sunlith.school.SchoolRecord);
    java.util.Map calendarIdentity(long);
    java.util.List calendars();
    java.util.List commit(io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolJournal);
    void disconnect(io.github.sunsind.sunlith.school.SchoolRecord,boolean);
    io.github.sunsind.sunlith.school.SchoolJournal plan(io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolFeed,java.time.LocalDate);
    java.util.List pruneHistory(io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolTerm,java.time.LocalDate);
    io.github.sunsind.sunlith.school.SchoolChangeSummary summary(io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolJournal);
    java.util.List verify(io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolJournal,boolean);
    java.util.List verify$default(io.github.sunsind.sunlith.school.SchoolCalendarWriter,io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolJournal,boolean,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolCalendarWriter$Companion {
    int batchBytes$app_distribution(java.util.ArrayList);
    boolean identity(io.github.sunsind.sunlith.school.SchoolOwned,long,java.util.Map,java.lang.String);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolChangeSummary {
    <init>(int,int,int,int);
    <init>(int,int,int,int,int,kotlin.jvm.internal.DefaultConstructorMarker);
    int getAdded();
    int getLinked();
    int getRemoved();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolDiff {
    io.github.sunsind.sunlith.school.SchoolDiff INSTANCE;
    java.util.List changes(java.util.List,java.util.List,java.time.LocalDate);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolError {
    io.github.sunsind.sunlith.school.SchoolError CALENDAR_CHANGED;
    io.github.sunsind.sunlith.school.SchoolError INVALID_DATA;
    io.github.sunsind.sunlith.school.SchoolError LEGACY_COLLISION;
    io.github.sunsind.sunlith.school.SchoolError MFA_REQUIRED;
    io.github.sunsind.sunlith.school.SchoolError NETWORK;
    io.github.sunsind.sunlith.school.SchoolError SIGN_IN;
    io.github.sunsind.sunlith.school.SchoolError STORAGE;
    io.github.sunsind.sunlith.school.SchoolError UNAVAILABLE;
    kotlin.enums.EnumEntries getEntries();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolFailure {
    io.github.sunsind.sunlith.school.SchoolError getReason();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolFeed {
    <init>(java.lang.String,io.github.sunsind.sunlith.school.SchoolTerm,java.util.List);
    java.util.List getItems();
    io.github.sunsind.sunlith.school.SchoolTerm getTerm();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolItem {
    <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.time.LocalDate,long,long,boolean,java.lang.String,java.util.List,java.util.List,java.util.List,boolean,int,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
    io.github.sunsind.sunlith.school.SchoolItem copy$default(io.github.sunsind.sunlith.school.SchoolItem,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.time.LocalDate,long,long,boolean,java.lang.String,java.util.List,java.util.List,java.util.List,boolean,int,java.lang.String,java.lang.String,int,java.lang.Object);
    boolean getAllDay();
    java.lang.String getCourse();
    java.time.LocalDate getDate();
    long getEndMillis();
    java.lang.String getGroup();
    java.lang.String getId();
    java.lang.String getKind();
    java.util.List getNotes();
    java.util.List getRooms();
    long getStartMillis();
    boolean getTeacherAbsent();
    java.util.List getTeachers();
    java.lang.String getTitle();
    java.lang.String getZone();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolJournal {
    java.util.List getAfter();
    java.util.List getBaseline();
    java.util.List getLinked();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolLogin {
    java.lang.String getToken();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolNotificationPreferences {
    <init>(boolean,java.util.Set);
    <init>(boolean,java.util.Set,int,kotlin.jvm.internal.DefaultConstructorMarker);
    io.github.sunsind.sunlith.school.SchoolNotificationPreferences copy$default(io.github.sunsind.sunlith.school.SchoolNotificationPreferences,boolean,java.util.Set,int,java.lang.Object);
    java.util.Set getCategories();
    boolean getEnabled();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolNotifications {
    <init>(android.content.Context);
    void changes(io.github.sunsind.sunlith.school.SchoolRecord,java.util.List);
    void clearAll();
    void clearReview();
    void failure(io.github.sunsind.sunlith.school.SchoolRecord,io.github.sunsind.sunlith.school.SchoolError);
    void review(io.github.sunsind.sunlith.school.SchoolRecord,java.util.List);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolOwned {
    io.github.sunsind.sunlith.school.SchoolItem getItem();
    java.lang.String getMarker();
    java.util.Map getRevision();
    long getRowId();
    java.lang.String getUid();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolPortalClient {
    io.github.sunsind.sunlith.school.SchoolPortalClient$Companion Companion;
    <init>(java.lang.String,java.lang.String,kotlin.jvm.functions.Function1);
    java.lang.Object fetch(java.lang.String,java.time.ZoneId,java.util.List,boolean,java.lang.String,kotlin.coroutines.Continuation);
    java.lang.Object fetch$default(io.github.sunsind.sunlith.school.SchoolPortalClient,java.lang.String,java.time.ZoneId,java.util.List,boolean,java.lang.String,kotlin.coroutines.Continuation,int,java.lang.Object);
    java.lang.Object login(java.lang.String,java.lang.String,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolPortalClient$Companion {
    java.net.URI normalize(java.lang.String);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolRecord {
    <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,long,java.lang.String,java.lang.String,java.lang.String,long,java.lang.String,java.util.List,java.util.List,boolean,java.lang.Long,io.github.sunsind.sunlith.school.SchoolNotificationPreferences,java.lang.String,io.github.sunsind.sunlith.school.SchoolJournal,java.util.Map,java.lang.String,java.lang.String,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
    io.github.sunsind.sunlith.school.SchoolRecord copy$default(io.github.sunsind.sunlith.school.SchoolRecord,java.lang.String,java.lang.String,java.lang.String,java.lang.String,long,java.lang.String,java.lang.String,java.lang.String,long,java.lang.String,java.util.List,java.util.List,boolean,java.lang.Long,io.github.sunsind.sunlith.school.SchoolNotificationPreferences,java.lang.String,io.github.sunsind.sunlith.school.SchoolJournal,java.util.Map,java.lang.String,java.lang.String,java.lang.String,int,java.lang.Object);
    java.lang.String getAccount();
    java.util.List getBaseline();
    long getCalendarId();
    java.lang.String getDisconnecting();
    boolean getInitialized();
    io.github.sunsind.sunlith.school.SchoolJournal getJournal();
    java.lang.String getLastFailure();
    java.lang.Long getLastSuccess();
    java.util.List getLedger();
    io.github.sunsind.sunlith.school.SchoolNotificationPreferences getNotifications();
    java.lang.String getPassword();
    java.lang.String getPendingReview();
    java.lang.String getPortal();
    java.lang.String getTermKey();
    java.lang.String getUsername();
}
-keep,allowobfuscation class io.github.sunsind.sunlith.school.SchoolScheduleController {
    java.lang.Object applyPendingChanges(kotlin.coroutines.Continuation);
    java.lang.Object connect(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Long,kotlin.coroutines.Continuation);
    java.lang.Object discardPendingChanges(kotlin.coroutines.Continuation);
    java.lang.Object disconnect(boolean,kotlin.coroutines.Continuation);
    kotlinx.coroutines.flow.StateFlow getState();
    java.lang.Object refresh(kotlin.coroutines.Continuation);
    java.lang.Object setCategory(io.github.sunsind.sunlith.school.SchoolAlert,boolean,kotlin.coroutines.Continuation);
    java.lang.Object setNotifications(io.github.sunsind.sunlith.school.SchoolNotificationPreferences,kotlin.coroutines.Continuation);
    java.lang.Object setNotificationsEnabled(boolean,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolScheduleManager {
    <init>(android.content.Context,kotlin.jvm.functions.Function2);
    <init>(android.content.Context,kotlin.jvm.functions.Function2,int,kotlin.jvm.internal.DefaultConstructorMarker);
    java.lang.Object applyPendingChanges(kotlin.coroutines.Continuation);
    java.lang.Object connect(java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Long,kotlin.coroutines.Continuation);
    java.lang.Object disconnect(boolean,kotlin.coroutines.Continuation);
    kotlinx.coroutines.flow.StateFlow getState();
    void onBackground();
    void onOpen();
    java.lang.Object refresh(kotlin.coroutines.Continuation);
    java.lang.Object refreshForJob$app_distribution(kotlin.coroutines.Continuation);
    java.lang.Object setCategory(io.github.sunsind.sunlith.school.SchoolAlert,boolean,kotlin.coroutines.Continuation);
    java.lang.Object setNotifications(io.github.sunsind.sunlith.school.SchoolNotificationPreferences,kotlin.coroutines.Continuation);
    java.lang.Object setNotificationsEnabled(boolean,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolScheduleState {
    <init>(boolean,boolean,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Long,io.github.sunsind.sunlith.school.SchoolError,java.lang.Long,java.util.List,boolean,int,io.github.sunsind.sunlith.school.SchoolNotificationPreferences,io.github.sunsind.sunlith.school.SchoolChangeSummary,int,kotlin.jvm.internal.DefaultConstructorMarker);
    io.github.sunsind.sunlith.school.SchoolScheduleState copy$default(io.github.sunsind.sunlith.school.SchoolScheduleState,boolean,boolean,java.lang.String,java.lang.String,java.lang.String,java.lang.String,java.lang.Long,io.github.sunsind.sunlith.school.SchoolError,java.lang.Long,java.util.List,boolean,int,io.github.sunsind.sunlith.school.SchoolNotificationPreferences,io.github.sunsind.sunlith.school.SchoolChangeSummary,int,java.lang.Object);
    java.lang.String getAccountName();
    java.util.List getAvailableCalendars();
    boolean getBusy();
    java.lang.Long getCalendarId();
    boolean getConnected();
    io.github.sunsind.sunlith.school.SchoolError getError();
    int getEventCount();
    java.lang.Long getLastSuccessMillis();
    io.github.sunsind.sunlith.school.SchoolNotificationPreferences getNotifications();
    io.github.sunsind.sunlith.school.SchoolChangeSummary getPendingChanges();
    java.lang.String getPortalAddress();
    java.lang.String getTermName();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolSource {
    io.github.sunsind.sunlith.school.SchoolSource INSTANCE;
    java.util.List absences(org.json.JSONArray,java.time.LocalDate,java.time.ZoneId);
    java.util.List agenda(org.json.JSONArray,io.github.sunsind.sunlith.school.SchoolTerm,java.time.ZoneId);
    java.util.List merge(java.util.List,java.util.List,io.github.sunsind.sunlith.school.SchoolTerm);
    java.lang.String wireDate(java.time.LocalDate);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolSourceKt {
    java.lang.String schoolHash(java.lang.String);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolStore {
    <init>(android.content.Context);
    void clear();
    io.github.sunsind.sunlith.school.SchoolRecord load();
    void save(io.github.sunsind.sunlith.school.SchoolRecord);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolSyncJobService {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.school.SchoolTerm {
    <init>(java.lang.String,java.lang.String,java.time.LocalDate,java.time.LocalDate);
    java.lang.String getKey();
    java.time.LocalDate getStart();
    java.util.List mondays();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.AppRefresh {
    io.github.sunsind.sunlith.system.AppRefresh INSTANCE;
    java.util.concurrent.ExecutorService getExecutor$app_distribution();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.CalendarHandoff$Rejected {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.CalendarHandoffKt {
    io.github.sunsind.sunlith.system.CalendarHandoff readCalendarHandoff$default(android.content.Intent,java.lang.String,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.CalendarReminderReceiver {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.LauncherIcons {
    io.github.sunsind.sunlith.system.LauncherIcons INSTANCE;
    android.content.ComponentName component(android.content.Context,io.github.sunsind.sunlith.system.LauncherIcons$Style);
    boolean consumeReopen(android.content.Context);
    java.util.List enabled(android.content.Context);
    java.util.List getAvailableStyles();
    io.github.sunsind.sunlith.system.LauncherIcons$Style getDefault();
    void reconcile(android.content.Context);
    boolean reopenPending(android.content.Context);
    io.github.sunsind.sunlith.system.LauncherIcons$Style saved(android.content.Context);
    boolean select(android.content.Context,io.github.sunsind.sunlith.system.LauncherIcons$Style);
    void switchSequentially$app_distribution(android.content.Context,io.github.sunsind.sunlith.system.LauncherIcons$Style,kotlin.jvm.functions.Function2);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.LauncherIcons$Style {
    io.github.sunsind.sunlith.system.LauncherIcons$Style Dark;
    io.github.sunsind.sunlith.system.LauncherIcons$Style Light;
    io.github.sunsind.sunlith.system.LauncherIcons$Style Neutral;
    kotlin.enums.EnumEntries getEntries();
    int getIcon();
    java.lang.String getKey();
    int getLabel();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.ReminderNotifications {
    io.github.sunsind.sunlith.system.ReminderNotifications INSTANCE;
    boolean allowed(android.content.Context);
    boolean post$default(io.github.sunsind.sunlith.system.ReminderNotifications,android.content.Context,io.github.sunsind.sunlith.data.CalendarEvent,int,boolean,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.ReminderScheduler {
    io.github.sunsind.sunlith.system.ReminderScheduler INSTANCE;
    void reschedule(android.content.Context);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.system.SettingsStore {
    <init>(android.content.Context);
    java.lang.String getAgendaDisplay();
    java.lang.String getAgendaRange();
    boolean getCollapseCalendarOnScroll();
    java.util.Map getCourseColorSlots();
    java.lang.String getDayLayout();
    boolean getGroupEmptyHours();
    boolean getHasChosenCalendars();
    boolean getKeepSelectedDate();
    java.lang.String getLayoutPreset();
    boolean getMaterialYou();
    java.lang.String getMonthLayout();
    boolean getNavigationAnimation();
    boolean getRemindersEnabled();
    java.util.Set getSelectedCalendarIds();
    boolean getShowNavigationArrows();
    java.lang.String getStartView();
    boolean getSwipeNavigation();
    java.lang.String getThemeMode();
    int getVisibleWeekDays();
    java.lang.String getWeekLayout();
    int getWidgetDays();
    int getWidgetOpacity();
    boolean getWidgetShowCalendarName();
    boolean getWidgetShowEmptyDays();
    void setAgendaDisplay(java.lang.String);
    void setAgendaRange(java.lang.String);
    void setCollapseCalendarOnScroll(boolean);
    void setCourseColorSlots(java.util.Map);
    void setDayLayout(java.lang.String);
    void setGroupEmptyHours(boolean);
    void setHasChosenCalendars(boolean);
    void setKeepSelectedDate(boolean);
    void setLayoutPreset(java.lang.String);
    void setMaterialYou(boolean);
    void setMonthLayout(java.lang.String);
    void setNavigationAnimation(boolean);
    void setRemindersEnabled(boolean);
    void setSelectedCalendarIds(java.util.Set);
    void setShowNavigationArrows(boolean);
    void setStartView(java.lang.String);
    void setSwipeNavigation(boolean);
    void setThemeMode(java.lang.String);
    void setVisibleWeekDays(int);
    void setWeekLayout(java.lang.String);
    void setWeekStartsMonday(boolean);
    void setWidgetDays(int);
    void setWidgetOpacity(int);
    void setWidgetShowCalendarName(boolean);
    void setWidgetShowEmptyDays(boolean);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.CalendarEventStyleKt {
    java.lang.Object awaitFocusStart(boolean,kotlin.coroutines.Continuation);
    io.github.sunsind.sunlith.ui.EventColors courseColors(int,boolean);
    io.github.sunsind.sunlith.data.CalendarEvent focusTarget(java.util.List,java.time.LocalDate,java.time.ZoneId);
    java.lang.Object scrollToFocus(androidx.compose.foundation.lazy.LazyListState,int,boolean,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.CalendarScreenKt {
    boolean rememberMotion(boolean,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.CalendarSwipeKt {
    void PagedPeriod(io.github.sunsind.sunlith.ui.PeriodSwipeState,long,androidx.compose.ui.Modifier,kotlin.jvm.functions.Function3,androidx.compose.runtime.Composer,int,int);
    void SlidingPeriod(io.github.sunsind.sunlith.ui.PeriodSwipeState,long,androidx.compose.ui.Modifier,kotlin.jvm.functions.Function3,androidx.compose.runtime.Composer,int,int);
    androidx.compose.ui.Modifier periodSwipe$default(androidx.compose.ui.Modifier,io.github.sunsind.sunlith.ui.PeriodSwipeState,boolean,int,java.lang.Object);
    io.github.sunsind.sunlith.ui.PeriodSwipeState rememberPeriodSwipe(boolean,boolean,kotlin.jvm.functions.Function1,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.CalendarTimelineKt {
    void Timeline(java.util.List,kotlin.jvm.functions.Function1,java.time.LocalDate,java.time.LocalDate,io.github.sunsind.sunlith.ui.Formats,boolean,androidx.compose.foundation.ScrollState,boolean,int,boolean,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function2,androidx.compose.ui.Modifier,io.github.sunsind.sunlith.ui.TimelineFocus,kotlin.jvm.functions.Function1,boolean,boolean,boolean,androidx.compose.runtime.MutableFloatState,androidx.compose.runtime.MutableIntState,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function2,androidx.compose.runtime.Composer,int,int,int,int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.ContinuousTimelineKt {
    void ContinuousTimeline(io.github.sunsind.sunlith.ui.SunlithViewModel,io.github.sunsind.sunlith.ui.Formats,java.time.LocalDate,boolean,androidx.compose.foundation.ScrollState,androidx.compose.ui.Modifier,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,androidx.compose.runtime.Composer,int,int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.EditorState {
    io.github.sunsind.sunlith.ui.EditorState copy$default(io.github.sunsind.sunlith.ui.EditorState,java.lang.Long,java.lang.Long,boolean,java.lang.String,java.lang.String,java.lang.String,boolean,java.time.LocalDate,java.time.LocalTime,java.time.LocalDate,java.time.LocalTime,java.lang.String,java.lang.String,java.lang.String,java.util.List,io.github.sunsind.sunlith.data.EventDraft,int,java.lang.Object);
    java.time.LocalDate getStartDate();
    java.lang.String getTitle();
    io.github.sunsind.sunlith.data.EventDraft toDraft(java.time.ZoneId);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.EventColors {
    long getContainer-0d7_KjU();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.FormatKt {
    java.time.LocalDate gridStart(java.time.YearMonth,boolean);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Formats {
    int $stable;
    <init>(android.content.Context,java.util.Locale);
    java.lang.String dayLong(java.time.LocalDate);
    java.lang.String dayMonth(java.time.LocalDate);
    java.lang.String numericDayMonth(java.time.LocalDate);
    java.lang.String timeRange(long,long);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.ImportUi {
    java.lang.String getError();
    io.github.sunsind.sunlith.data.ImportPreview getPreview();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.PeriodSwipeState {
    void setWidth(int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.SchoolScheduleScreenKt {
    void SchoolScheduleScreen(io.github.sunsind.sunlith.school.SchoolScheduleController,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function0,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen {
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen$Calendars {
    io.github.sunsind.sunlith.ui.Screen$Calendars INSTANCE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen$Details {
    io.github.sunsind.sunlith.data.CalendarEvent getEvent();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen$DeviceLink {
    io.github.sunsind.sunlith.ui.Screen$DeviceLink INSTANCE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen$Editor {
    io.github.sunsind.sunlith.ui.Screen$Editor INSTANCE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen$SchoolSchedule {
    io.github.sunsind.sunlith.ui.Screen$SchoolSchedule INSTANCE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.Screen$Settings {
    io.github.sunsind.sunlith.ui.Screen$Settings INSTANCE;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.SunlithAppKt {
    void SunlithApp(io.github.sunsind.sunlith.ui.SunlithViewModel,androidx.compose.runtime.Composer,int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.SunlithPalette {
    long getAccent-0d7_KjU();
    long getBackground-0d7_KjU();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.SunlithViewModel {
    int $stable;
    void applyLayoutPreset(java.lang.String);
    void back();
    void changeAgendaDisplay(java.lang.String);
    void changeAgendaRange(java.lang.String);
    void changeCollapseCalendarOnScroll(boolean);
    void changeDayLayout(java.lang.String);
    void changeGroupEmptyHours(boolean);
    void changeKeepSelectedDate(boolean);
    void changeMaterialYou(boolean);
    void changeMonthLayout(java.lang.String);
    void changeNavigationAnimation(boolean);
    void changeShowNavigationArrows(boolean);
    void changeStartView(java.lang.String);
    void changeSwipeNavigation(boolean);
    void changeViewMode(io.github.sunsind.sunlith.ui.ViewMode);
    void changeVisibleWeekDays(int);
    void changeWeekLayout(java.lang.String);
    void closeEditor();
    boolean coversRange(java.time.LocalDate,java.time.LocalDate);
    void deliverPendingLink();
    void extendAgenda(boolean);
    void finishAiIntro();
    void finishLayoutSetup();
    java.util.Set getActiveIds();
    java.lang.String getAgendaDisplay();
    java.lang.String getAgendaRange();
    boolean getAiIntroDone();
    java.util.Map getCourseColorSlots();
    int getDayFocusRequest();
    java.lang.String getDayLayout();
    io.github.sunsind.sunlith.ui.EditorState getEditor();
    boolean getEditorDirty();
    java.util.List getEvents();
    java.util.Map getEventsByDay();
    boolean getExtendingAgenda();
    java.time.LocalDate getGridLoadedFrom();
    java.time.LocalDate getGridLoadedTo();
    boolean getGroupEmptyHours();
    int getHandledTimelineNavigation();
    boolean getHasDayParent();
    boolean getHasPermission();
    io.github.sunsind.sunlith.ui.ImportUi getImportUi();
    boolean getKeepSelectedDate();
    boolean getLauncherIconBusy();
    java.lang.String getLayoutPreset();
    boolean getLayoutSetupDone();
    java.lang.String getLoadError();
    boolean getLoaded();
    java.time.LocalDate getLoadedFrom();
    java.time.LocalDate getLoadedTo();
    boolean getLoadingEvents();
    java.time.YearMonth getMonth();
    java.lang.String getMonthLayout();
    boolean getNavigationAnimation();
    boolean getNeedsNotificationStep();
    androidx.compose.runtime.MutableIntState getPagedTimelineTodayHandled$app_distribution();
    boolean getPendingDayFocus();
    boolean getSearchLoading();
    java.util.List getSearchResults();
    java.time.LocalDate getSelectedDate();
    boolean getShowNavigationArrows();
    androidx.compose.runtime.snapshots.SnapshotStateList getStack();
    boolean getSwipeNavigation();
    int getTimelineNavigationRequest();
    java.time.LocalDate getTimelineViewportEnd();
    java.time.LocalDate getTimelineViewportStart();
    java.time.LocalDate getToday();
    int getTodayRequest();
    io.github.sunsind.sunlith.ui.ViewMode getViewMode();
    int getVisibleWeekDays();
    java.lang.String getWeekLayout();
    boolean getWeekStartsMonday();
    boolean getWidgetShowCalendarName();
    void goToday();
    void handleLink$default(io.github.sunsind.sunlith.ui.SunlithViewModel,long,long,boolean,java.time.LocalDate,int,java.lang.Object);
    void handleSchoolLink(java.time.LocalDate);
    void newEvent(java.time.LocalDate);
    void open(io.github.sunsind.sunlith.ui.Screen);
    void openDay(java.time.LocalDate);
    void openSearch();
    void refreshCurrentDate$app_distribution(java.time.LocalDate);
    void reload();
    void select(java.time.LocalDate);
    void selectAndFocus(java.time.LocalDate);
    void setMondayFirst(boolean);
    void setTheme(java.lang.String);
    void step(int);
    void updateEditor(kotlin.jvm.functions.Function1);
    void updateSearch(java.lang.String);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.ThemeKt {
    void SunlithTheme(java.lang.String,boolean,kotlin.jvm.functions.Function2,androidx.compose.runtime.Composer,int,int);
    io.github.sunsind.sunlith.ui.SunlithPalette getDarkPalette();
    io.github.sunsind.sunlith.ui.SunlithPalette getLightPalette();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.TimelineAxis {
    <init>(float,java.util.List,boolean,float);
    float minuteToY(int);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.TimelineFocus {
    <init>(java.time.LocalDate,int,kotlin.jvm.functions.Function0,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.ui.ViewMode {
    io.github.sunsind.sunlith.ui.ViewMode Day;
    io.github.sunsind.sunlith.ui.ViewMode Month;
    io.github.sunsind.sunlith.ui.ViewMode Week;
    kotlin.enums.EnumEntries getEntries();
    io.github.sunsind.sunlith.ui.ViewMode[] values();
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.widget.AgendaWidgetReceiver {
    io.github.sunsind.sunlith.widget.AgendaWidgetReceiver$Companion Companion;
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.widget.AgendaWidgetReceiver$Companion {
    void updateAll(android.content.Context);
}
-keep,allowoptimization,allowobfuscation class io.github.sunsind.sunlith.widget.WidgetConfigureActivity {
}
-keep,allowoptimization,allowobfuscation class kotlin.ExceptionsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.ExceptionsKt__ExceptionsKt {
    java.lang.String stackTraceToString(java.lang.Throwable);
}
-keep,allowoptimization,allowobfuscation class kotlin.Lazy {
    java.lang.Object getValue();
}
-keep,allowoptimization,allowobfuscation class kotlin.LazyKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.LazyKt__LazyJVMKt {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep,allowoptimization,allowobfuscation class kotlin.NoWhenBranchMatchedException {
    <init>();
}
-keep,allowoptimization,allowobfuscation class kotlin.Pair {
    java.lang.Object component1();
    java.lang.Object component2();
    java.lang.Object getFirst();
    java.lang.Object getSecond();
}
-keep,allowoptimization,allowobfuscation class kotlin.Result {
    kotlin.Result$Companion Companion;
    java.lang.Object constructor-impl(java.lang.Object);
    java.lang.Throwable exceptionOrNull-impl(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.ResultKt {
    java.lang.Object createFailure(java.lang.Throwable);
    void throwOnFailure(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.Triple {
    <init>(java.lang.Object,java.lang.Object,java.lang.Object);
    java.lang.Object component1();
    java.lang.Object component2();
    java.lang.Object component3();
}
-keep,allowoptimization,allowobfuscation class kotlin.TuplesKt {
    kotlin.Pair to(java.lang.Object,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.Unit {
    kotlin.Unit INSTANCE;
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.ArraysKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.ArraysKt___ArraysJvmKt {
    byte[] plus(byte[],byte[]);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.ArraysKt___ArraysKt {
    int getLastIndex(byte[]);
    byte last(byte[]);
    java.util.List sorted(java.lang.Comparable[]);
    java.util.List toList(long[]);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.CollectionsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.CollectionsKt__CollectionsJVMKt {
    java.util.List build(java.util.List);
    java.util.List createListBuilder();
    java.util.List listOf(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.CollectionsKt__CollectionsKt {
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object[]);
    java.util.List listOfNotNull(java.lang.Object[]);
    void throwCountOverflow();
    void throwIndexOverflow();
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.CollectionsKt__IterablesKt {
    int collectionSizeOrDefault(java.lang.Iterable,int);
    java.util.List flatten(java.lang.Iterable);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.CollectionsKt__MutableCollectionsKt {
    boolean addAll(java.util.Collection,java.lang.Iterable);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.CollectionsKt___CollectionsKt {
    java.util.List chunked(java.lang.Iterable,int);
    java.util.List distinct(java.lang.Iterable);
    java.util.List drop(java.lang.Iterable,int);
    java.lang.Object first(java.util.List);
    java.lang.Object firstOrNull(java.util.List);
    java.lang.String joinToString$default(java.lang.Iterable,java.lang.CharSequence,java.lang.CharSequence,java.lang.CharSequence,int,java.lang.CharSequence,kotlin.jvm.functions.Function1,int,java.lang.Object);
    java.lang.Object last(java.util.List);
    java.lang.Object lastOrNull(java.util.List);
    java.lang.Comparable maxOrNull(java.lang.Iterable);
    java.lang.Comparable minOrNull(java.lang.Iterable);
    java.util.List plus(java.util.Collection,java.lang.Iterable);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List reversed(java.lang.Iterable);
    java.lang.Object single(java.util.List);
    java.util.List sorted(java.lang.Iterable);
    java.util.List sortedWith(java.lang.Iterable,java.util.Comparator);
    java.util.List toList(java.lang.Iterable);
    java.util.Set toSet(java.lang.Iterable);
    java.util.List zipWithNext(java.lang.Iterable);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.IntIterator {
    int nextInt();
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.LongIterator {
    long nextLong();
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.MapsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.MapsKt__MapsJVMKt {
    java.util.Map build(java.util.Map);
    java.util.Map createMapBuilder();
    int mapCapacity(int);
    java.util.Map mapOf(kotlin.Pair);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.MapsKt__MapsKt {
    java.util.Map emptyMap();
    java.lang.Object getValue(java.util.Map,java.lang.Object);
    java.util.LinkedHashMap linkedMapOf(kotlin.Pair[]);
    java.util.Map mapOf(kotlin.Pair[]);
    java.util.Map minus(java.util.Map,java.lang.Object);
    java.util.Map plus(java.util.Map,java.util.Map);
    java.util.Map plus(java.util.Map,kotlin.Pair);
    java.util.Map toMap(java.util.Map);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.SetsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.SetsKt__SetsJVMKt {
    java.util.Set setOf(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.SetsKt__SetsKt {
    java.util.Set emptySet();
    java.util.Set setOf(java.lang.Object[]);
}
-keep,allowoptimization,allowobfuscation class kotlin.collections.SetsKt___SetsKt {
    java.util.Set minus(java.util.Set,java.lang.Iterable);
    java.util.Set minus(java.util.Set,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.comparisons.ComparisonsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.comparisons.ComparisonsKt__ComparisonsKt {
    int compareValues(java.lang.Comparable,java.lang.Comparable);
}
-keep,allowoptimization,allowobfuscation class kotlin.comparisons.ComparisonsKt___ComparisonsJvmKt {
    java.lang.Comparable maxOf(java.lang.Comparable,java.lang.Comparable);
    java.lang.Comparable minOf(java.lang.Comparable,java.lang.Comparable);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.Continuation {
    kotlin.coroutines.CoroutineContext getContext();
    void resumeWith(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.ContinuationKt {
    kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.CoroutineContext {
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.EmptyCoroutineContext {
    kotlin.coroutines.EmptyCoroutineContext INSTANCE;
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt {
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
    java.lang.Object getCOROUTINE_SUSPENDED();
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    kotlin.coroutines.Continuation create(java.lang.Object,kotlin.coroutines.Continuation);
    kotlin.coroutines.Continuation create(kotlin.coroutines.Continuation);
    java.lang.Object invokeSuspend(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.Boxing {
    java.lang.Boolean boxBoolean(boolean);
    java.lang.Integer boxInt(int);
    java.lang.Long boxLong(long);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.ContinuationImpl {
    <init>(kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.DebugProbesKt {
    void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.SpillingKt {
    java.lang.Object nullOutSpilledVariable(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.SuspendFunction {
}
-keep,allowoptimization,allowobfuscation class kotlin.coroutines.jvm.internal.SuspendLambda {
    <init>(int,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlin.io.ByteStreamsKt {
    byte[] readBytes(java.io.InputStream);
}
-keep,allowoptimization,allowobfuscation class kotlin.io.CloseableKt {
    void closeFinally(java.io.Closeable,java.lang.Throwable);
}
-keep,allowoptimization,allowobfuscation class kotlin.io.FilesKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.io.FilesKt__FileReadWriteKt {
    byte[] readBytes(java.io.File);
    java.lang.String readText$default(java.io.File,java.nio.charset.Charset,int,java.lang.Object);
    void writeBytes(java.io.File,byte[]);
    void writeText$default(java.io.File,java.lang.String,java.nio.charset.Charset,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.io.FilesKt__FileTreeWalkKt {
    kotlin.io.FileTreeWalk walkTopDown(java.io.File);
}
-keep,allowoptimization,allowobfuscation class kotlin.io.FilesKt__UtilsKt {
    boolean deleteRecursively(java.io.File);
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.functions.Function0 {
    java.lang.Object invoke();
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.functions.Function1 {
    java.lang.Object invoke(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.functions.Function2 {
    java.lang.Object invoke(java.lang.Object,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.functions.Function3 {
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.functions.Function4 {
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.FunctionReferenceImpl {
    <init>(int,java.lang.Class,java.lang.String,java.lang.String,int);
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Intrinsics {
    boolean areEqual(java.lang.Object,java.lang.Object);
    void checkNotNull(java.lang.Object);
    void checkNotNull(java.lang.Object,java.lang.String);
    void checkNotNullExpressionValue(java.lang.Object,java.lang.String);
    void checkNotNullParameter(java.lang.Object,java.lang.String);
    void checkParameterIsNotNull(java.lang.Object,java.lang.String);
    void throwNpe();
    void throwUninitializedPropertyAccessException(java.lang.String);
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Intrinsics$Kotlin {
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Lambda {
    <init>(int);
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Ref {
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Ref$BooleanRef {
    boolean element;
    <init>();
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Ref$FloatRef {
    float element;
    <init>();
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Ref$IntRef {
    int element;
    <init>();
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Ref$LongRef {
    long element;
    <init>();
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.Ref$ObjectRef {
    java.lang.Object element;
    <init>();
}
-keep,allowoptimization,allowobfuscation class kotlin.jvm.internal.StringCompanionObject {
    kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keep,allowoptimization,allowobfuscation class kotlin.math.MathKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.math.MathKt__MathJVMKt {
    int roundToInt(float);
}
-keep,allowoptimization,allowobfuscation class kotlin.ranges.IntProgression {
    int getFirst();
    int getLast();
    int getStep();
}
-keep,allowoptimization,allowobfuscation class kotlin.ranges.IntRange {
    <init>(int,int);
}
-keep,allowoptimization,allowobfuscation class kotlin.ranges.LongRange {
    <init>(long,long);
}
-keep,allowoptimization,allowobfuscation class kotlin.ranges.RangesKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.ranges.RangesKt___RangesKt {
    int coerceAtLeast(int,int);
    long coerceAtLeast(long,long);
    int coerceAtMost(int,int);
    int coerceIn(int,int,int);
    kotlin.ranges.IntProgression step(kotlin.ranges.IntProgression,int);
    kotlin.ranges.IntRange until(int,int);
    kotlin.ranges.LongRange until(long,long);
}
-keep,allowoptimization,allowobfuscation class kotlin.sequences.Sequence {
    java.util.Iterator iterator();
}
-keep,allowoptimization,allowobfuscation class kotlin.sequences.SequencesKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.sequences.SequencesKt__SequencesKt {
    kotlin.sequences.Sequence generateSequence(java.lang.Object,kotlin.jvm.functions.Function1);
}
-keep,allowoptimization,allowobfuscation class kotlin.sequences.SequencesKt___SequencesKt {
    kotlin.sequences.Sequence filter(kotlin.sequences.Sequence,kotlin.jvm.functions.Function1);
    java.lang.Object first(kotlin.sequences.Sequence);
    kotlin.sequences.Sequence take(kotlin.sequences.Sequence,int);
}
-keep,allowoptimization,allowobfuscation class kotlin.text.Charsets {
    java.nio.charset.Charset US_ASCII;
    java.nio.charset.Charset UTF_8;
}
-keep,allowoptimization,allowobfuscation class kotlin.text.Regex {
    <init>(java.lang.String);
    boolean matches(java.lang.CharSequence);
}
-keep,allowoptimization,allowobfuscation class kotlin.text.StringsKt {
}
-keep,allowoptimization,allowobfuscation class kotlin.text.StringsKt__StringsJVMKt {
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlin.text.StringsKt__StringsKt {
    boolean contains$default(java.lang.CharSequence,java.lang.CharSequence,boolean,int,java.lang.Object);
    int getLastIndex(java.lang.CharSequence);
    int indexOf$default(java.lang.CharSequence,java.lang.String,int,boolean,int,java.lang.Object);
    boolean isBlank(java.lang.CharSequence);
    kotlin.sequences.Sequence lineSequence(java.lang.CharSequence);
    java.util.List lines(java.lang.CharSequence);
    java.lang.String padStart(java.lang.String,int,char);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
    java.lang.String substringAfter$default(java.lang.String,java.lang.String,java.lang.String,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,java.lang.String,java.lang.String,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
}
-keep,allowoptimization,allowobfuscation class kotlin.text.StringsKt___StringsKt {
    java.lang.String take(java.lang.String,int);
}
-keep,allowoptimization,allowobfuscation class kotlin.time.Duration {
    kotlin.time.Duration$Companion Companion;
}
-keep,allowoptimization,allowobfuscation class kotlin.time.Duration$Companion {
}
-keep,allowoptimization,allowobfuscation class kotlin.time.DurationKt {
    long toDuration(int,kotlin.time.DurationUnit);
}
-keep,allowoptimization,allowobfuscation class kotlin.time.DurationUnit {
    kotlin.time.DurationUnit SECONDS;
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.AwaitKt {
    java.lang.Object awaitAll(java.util.Collection,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.BuildersKt {
    kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2);
    kotlinx.coroutines.Deferred async$default(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2,int,java.lang.Object);
    kotlinx.coroutines.Job launch$default(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2,int,java.lang.Object);
    java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2);
    java.lang.Object runBlocking$default(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
    java.lang.Object withContext(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CancellableContinuation {
    void invokeOnCancellation(kotlin.jvm.functions.Function1);
    void resume(java.lang.Object,kotlin.jvm.functions.Function1);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CancellableContinuation$DefaultImpls {
    boolean cancel$default(kotlinx.coroutines.CancellableContinuation,java.lang.Throwable,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CancellableContinuationImpl {
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CompletableDeferred {
    boolean complete(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CompletableDeferredKt {
    kotlinx.coroutines.CompletableDeferred CompletableDeferred$default(kotlinx.coroutines.Job,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CoroutineDispatcher {
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CoroutineScope {
    kotlin.coroutines.CoroutineContext getCoroutineContext();
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CoroutineScopeKt {
    java.lang.Object coroutineScope(kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.CoroutineStart {
    kotlinx.coroutines.CoroutineStart DEFAULT;
    kotlinx.coroutines.CoroutineStart UNDISPATCHED;
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.Deferred {
    java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.DelayKt {
    java.lang.Object delay(long,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.Dispatchers {
    kotlinx.coroutines.CoroutineDispatcher getDefault();
    kotlinx.coroutines.CoroutineDispatcher getIO();
    kotlinx.coroutines.MainCoroutineDispatcher getMain();
    kotlinx.coroutines.CoroutineDispatcher getUnconfined();
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.ExecutorsKt {
    kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.Job {
    boolean isCancelled();
    java.lang.Object join(kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.Job$DefaultImpls {
    void cancel$default(kotlinx.coroutines.Job,java.util.concurrent.CancellationException,int,java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.JobKt {
    java.lang.Object cancelAndJoin(kotlinx.coroutines.Job,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.TimeoutKt {
    java.lang.Object withTimeout(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
    java.lang.Object withTimeout-KLykuaI(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.flow.Flow {
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.flow.FlowKt {
    java.lang.Object first(kotlinx.coroutines.flow.Flow,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.flow.MutableStateFlow {
    java.lang.Object getValue();
    void setValue(java.lang.Object);
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.flow.StateFlow {
    java.lang.Object getValue();
}
-keep,allowoptimization,allowobfuscation class kotlinx.coroutines.flow.StateFlowKt {
    kotlinx.coroutines.flow.MutableStateFlow MutableStateFlow(java.lang.Object);
}

# Reflection is deliberate in the delayed-provider, reload-count and TLS acceptance fixtures.
-keepclassmembers class io.github.sunsind.sunlith.ui.SunlithViewModel {
    long loadGeneration;
    io.github.sunsind.sunlith.data.CalendarRepository repo;
}
-keepclassmembers class io.github.sunsind.sunlith.device.DeviceLinkServer {
    java.util.concurrent.ConcurrentHashMap$KeySetView clients;
}
