package com.cookiejarapps.android.smartcookieweb.addons

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.AdapterView.OnItemSelectedListener
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cookiejarapps.android.smartcookieweb.R
import com.cookiejarapps.android.smartcookieweb.browser.AddonSortType
import com.cookiejarapps.android.smartcookieweb.ext.components
import com.cookiejarapps.android.smartcookieweb.preferences.UserPreferences
import kotlinx.android.synthetic.main.fragment_add_ons.*
import kotlinx.android.synthetic.main.fragment_add_ons.view.*
import kotlinx.android.synthetic.main.overlay_add_on_progress.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import mozilla.components.feature.addons.Addon
import mozilla.components.feature.addons.AddonManagerException
import mozilla.components.feature.addons.ui.*
import mozilla.components.support.ktx.android.content.res.resolveAttribute
import java.util.*
import java.util.concurrent.CancellationException
import kotlin.collections.ArrayList


// Fragment used for managing add-ons.

class AddonsFragment : Fragment(), AddonsManagerAdapterDelegate {
    private lateinit var recyclerView: RecyclerView
    private lateinit var spinner: Spinner
    private val scope = CoroutineScope(Dispatchers.IO)
    private var adapter: AddonsAdapter? = null
    private var addons: List<Addon>? = null
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setHasOptionsMenu(true)
        return inflater.inflate(R.layout.fragment_add_ons, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        super.onViewCreated(rootView, savedInstanceState)
        bindRecyclerView(rootView)
    }

    override fun onStart() {
        super.onStart()

        this@AddonsFragment.view?.let { view ->
            bindSpinner(view)
            bindRecyclerView(view)
        }

        findPreviousPermissionDialogFragment()?.let { dialog ->
            dialog.onPositiveButtonClicked = onConfirmPermissionButtonClicked
        }

        findPreviousInstallationDialogFragment()?.let { dialog ->
            dialog.onConfirmButtonClicked = onConfirmInstallationButtonClicked
            dialog.addonCollectionProvider = requireContext().components.addonCollectionProvider
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.search_menu, menu)
        val searchItem = menu.findItem(R.id.search)
        val searchView: SearchView = searchItem.actionView as SearchView
        searchView.imeOptions = EditorInfo.IME_ACTION_DONE
        searchView.queryHint = getString(R.string.search)

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return filterAddonByQuery(query.trim())
            }

            override fun onQueryTextChange(query: String): Boolean {
                return filterAddonByQuery(query.trim())
            }
        })
    }

    private fun filterAddonByQuery(query: String): Boolean {
        val filteredList = arrayListOf<Addon>()

        addons?.forEach { addon ->
            val names = addon.translatableName
            names["en-us"]?.let { name ->
                if (name.toLowerCase(Locale.ENGLISH).contains(query.toLowerCase(Locale.ENGLISH))) {
                    filteredList.add(addon)
                }
            }
        }

        adapter?.updateAddons(filteredList)

        if (filteredList.isEmpty()) {
            view?.let { view ->
                view.add_ons_no_results.visibility = View.VISIBLE
                view.add_ons_list.visibility = View.GONE
            }
        } else {
            view?.let { view ->
                view.add_ons_no_results.visibility = View.GONE
                view.add_ons_list.visibility = View.VISIBLE
            }
        }

        return true
    }

    private fun updateSorting(){
        if(UserPreferences(requireContext()).addonSort == AddonSortType.RATING.ordinal){
            Collections.sort(
                addons
            ) { item1, item2 ->
                if (item1.rating != null || item2.rating != null) {
                    if(item1.rating!!.average == 0F && item2.rating!!.average == 0F){
                        item1.translatableName["en-us"]!!.compareTo(item2.translatableName["en-us"]!!)
                    }
                    else if (item1.rating!!.average == item2.rating!!.average) {
                        -item1.rating!!.reviews.compareTo(item2.rating!!.reviews)
                    }
                    else {
                        -item1.rating!!.average.compareTo(item2.rating!!.average)
                    }
                } else{
                    if(item1.translatableName["en-us"] != null && item2.translatableName["en-us"] != null) {
                        item1.translatableName["en-us"]!!.compareTo(item2.translatableName["en-us"]!!)
                    }
                    else {
                        item1.id.compareTo(item2.id)
                    }
                }
            }
            addons?.let { adapter?.updateAddons(it) }
            recyclerView.adapter?.notifyDataSetChanged()
        }
        else if(UserPreferences(requireContext()).addonSort == AddonSortType.A_Z.ordinal){
                Collections.sort(
                    addons
                ) { item1, item2 ->
                    if(item1.translatableName["en-us"] != null && item2.translatableName["en-us"] != null){
                        item1.translatableName["en-us"]!!.compareTo(item2.translatableName["en-us"]!!, true)
                    }
                    else{
                        item1.id.compareTo(item2.id)
                    }
                }
                addons?.let { adapter?.updateAddons(it) }
                recyclerView.adapter?.notifyDataSetChanged()
        }
        else if(UserPreferences(requireContext()).addonSort == AddonSortType.Z_A.ordinal){
            Collections.sort(
                addons
            ) { item1, item2 ->
                if(item1.translatableName["en-us"] != null && item2.translatableName["en-us"] != null){
                    item1.translatableName["en-us"]!!.compareTo(item2.translatableName["en-us"]!!, true)
                }
                else{
                    item1.id.compareTo(item2.id)
                }
            }
            addons = addons?.reversed()
            addons?.let { adapter?.updateAddons(it) }
            recyclerView.adapter?.notifyDataSetChanged()
        }
    }

    private fun bindSpinner(rootView: View) {
        val users = arrayOf(
            requireContext().resources.getString(R.string.sort_rating),
            requireContext().resources.getString(R.string.sort_a_z),
            requireContext().resources.getString(R.string.sort_z_a)
        )

        spinner = rootView.findViewById(R.id.sort_spinner)
        val adapter: ArrayAdapter<String> =
            ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, users)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.setAdapter(adapter)
        spinner.setSelection(UserPreferences(requireContext()).addonSort)

        spinner.onItemSelectedListener = object : OnItemSelectedListener {
            override fun onItemSelected(
                adapterView: AdapterView<*>?,
                view: View?,
                position: Int,
                row: Long
            ) {
                UserPreferences(requireContext()).addonSort = position
                updateSorting()
            }

            override fun onNothingSelected(adapterView: AdapterView<*>?) {
                return
            }
        }
    }

    private fun bindRecyclerView(rootView: View) {
        recyclerView = rootView.findViewById(R.id.add_ons_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        scope.launch {
            try {
                addons = requireContext().components.addonManager.getAddons()

                val context = requireContext()
                val addonCollectionProvider = context.components.addonCollectionProvider

                val style = AddonsAdapter.Style(
                    dividerColor = context.theme.resolveAttribute(android.R.attr.textColorSecondary),
                    dividerHeight = R.dimen.mozac_browser_menu_item_divider_height,
                    addonNameTextColor = context.theme.resolveAttribute(android.R.attr.textColorPrimary),
                    sectionsTextColor = context.theme.resolveAttribute(android.R.attr.textColorPrimary)
                )

                scope.launch(Dispatchers.Main) {
                    view?.add_ons_no_results?.isVisible = false
                    view?.add_ons_loading?.isVisible = false
                    updateSorting()

                    if (adapter == null) {
                        adapter = AddonsAdapter(
                            addonCollectionProvider = addonCollectionProvider,
                            addonsManagerDelegate = this@AddonsFragment,
                            addons = addons!!,
                            style = style
                        )
                        recyclerView.adapter = adapter


                        val bundle: Bundle? = arguments
                        if (bundle?.getString("ADDON_ID") != null) {
                            val addonId = bundle.getString("ADDON_ID", "")
                            installAddonById(addons!!, addonId)
                        }
                    } else {
                        adapter?.updateAddons(addons!!)
                    }
                }
            } catch (e: AddonManagerException) {
                scope.launch(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        R.string.mozac_feature_addons_failed_to_query_add_ons,
                        Toast.LENGTH_SHORT
                    ).show()
                    view?.add_ons_no_results?.isVisible = true
                }
            }
        }
    }

    @VisibleForTesting
    internal fun installAddonById(supportedAddons: List<Addon>, id: String) {
        val addonToInstall = supportedAddons.find { it.downloadId == id }
        if (addonToInstall == null) {
            val builder = AlertDialog.Builder(requireContext())
            builder.setMessage(resources.getString(R.string.addon_not_available))
                    .setCancelable(false)
                    .setPositiveButton(R.string.mozac_feature_prompts_ok) { dialog, id ->
                        dialog.cancel()
                    }

            val alert: AlertDialog = builder.create()
            alert.show()
        } else {
            if (addonToInstall.isInstalled()) {
                // error
            } else {
                showPermissionDialog(addonToInstall)
            }
        }
    }

    override fun onAddonItemClicked(addon: Addon) {
        val context = requireContext()

        if (addon.isInstalled()) {
            val intent = Intent(context, InstalledAddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            context.startActivity(intent)
        } else {
            val intent = Intent(context, AddonDetailsActivity::class.java)
            intent.putExtra("add_on", addon)
            this.startActivity(intent)
        }
    }

    override fun onInstallAddonButtonClicked(addon: Addon) {
        showPermissionDialog(addon)
    }

    override fun onNotYetSupportedSectionClicked(unsupportedAddons: List<Addon>) {
        val intent = Intent(context, NotYetSupportedAddonActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.putExtra("add_ons", ArrayList(unsupportedAddons))
        requireContext().startActivity(intent)
    }

    private fun isAlreadyADialogCreated(): Boolean {
        return findPreviousPermissionDialogFragment() != null && findPreviousInstallationDialogFragment() != null
    }

    private fun findPreviousPermissionDialogFragment(): PermissionsDialogFragment? {
        return parentFragmentManager.findFragmentByTag(
            PERMISSIONS_DIALOG_FRAGMENT_TAG
        ) as? PermissionsDialogFragment
    }

    private fun findPreviousInstallationDialogFragment(): AddonInstallationDialogFragment? {
        return parentFragmentManager.findFragmentByTag(
            INSTALLATION_DIALOG_FRAGMENT_TAG
        ) as? AddonInstallationDialogFragment
    }

    private fun showPermissionDialog(addon: Addon) {
        if (isInstallationInProgress) {
            return
        }

        val dialog = PermissionsDialogFragment.newInstance(
            addon = addon,
            onPositiveButtonClicked = onConfirmPermissionButtonClicked
        )

        if (!isAlreadyADialogCreated() && isAdded) {
            dialog.show(parentFragmentManager, PERMISSIONS_DIALOG_FRAGMENT_TAG)
        }
    }

    private fun showInstallationDialog(addon: Addon) {
        if (isInstallationInProgress) {
            return
        }
        val addonCollectionProvider = requireContext().components.addonCollectionProvider
        val dialog = AddonInstallationDialogFragment.newInstance(
            addon = addon,
            addonCollectionProvider = addonCollectionProvider,
            onConfirmButtonClicked = onConfirmInstallationButtonClicked
        )

        if (!isAlreadyADialogCreated() && isAdded) {
            dialog.show(parentFragmentManager, INSTALLATION_DIALOG_FRAGMENT_TAG)
        }
    }

    private val onConfirmInstallationButtonClicked: ((Addon, Boolean) -> Unit) = { addon, allowInPrivateBrowsing ->
        if (allowInPrivateBrowsing) {
            requireContext().components.addonManager.setAddonAllowedInPrivateBrowsing(
                addon,
                allowInPrivateBrowsing
            )
        }
    }

    private val onConfirmPermissionButtonClicked: ((Addon) -> Unit) = { addon ->
        addonProgressOverlay.visibility = View.VISIBLE
        isInstallationInProgress = true

        val installOperation = requireContext().components.addonManager.installAddon(
            addon,
            onSuccess = { installedAddon ->
                context?.let {
                    adapter?.updateAddon(installedAddon)
                    addonProgressOverlay.visibility = View.GONE
                    isInstallationInProgress = false
                    showInstallationDialog(installedAddon)
                }
            },
            onError = { _, e ->
                // No need to display an error message if installation was cancelled by the user.
                if (e !is CancellationException) {
                    Toast.makeText(
                        requireContext(), getString(
                            R.string.mozac_feature_addons_failed_to_install,
                            addon.translateName(requireContext())
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                addonProgressOverlay.visibility = View.GONE
                isInstallationInProgress = false
            }
        )

        addonProgressOverlay.cancel_button.setOnClickListener {
            MainScope().launch {
                // Hide the installation progress overlay once cancellation is successful.
                if (installOperation.cancel().await()) {
                    addonProgressOverlay.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Whether or not an add-on installation is in progress.
     */
    private var isInstallationInProgress = false

    companion object {
        private const val PERMISSIONS_DIALOG_FRAGMENT_TAG = "ADDONS_PERMISSIONS_DIALOG_FRAGMENT"
        private const val INSTALLATION_DIALOG_FRAGMENT_TAG = "ADDONS_INSTALLATION_DIALOG_FRAGMENT"
    }
}
