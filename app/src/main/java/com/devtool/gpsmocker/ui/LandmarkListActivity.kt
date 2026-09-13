package com.devtool.gpsmocker.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.devtool.gpsmocker.R
import com.devtool.gpsmocker.databinding.ActivityLandmarkListBinding
import com.devtool.gpsmocker.db.LandmarkEntity
import com.devtool.gpsmocker.utils.WikiLandmarkHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 地標管理畫面 — 瀏覽 / 搜尋所有已下載的地標，並可逐筆刪除或全部清空。
 */
class LandmarkListActivity : AppCompatActivity() {

    private lateinit var b: ActivityLandmarkListBinding
    private lateinit var adapter: LandmarkAdapter
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLandmarkListBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = LandmarkAdapter { landmark -> confirmDeleteOne(landmark) }
        b.rvLandmarks.layoutManager = LinearLayoutManager(this)
        b.rvLandmarks.adapter = adapter

        b.btnBack.setOnClickListener { finish() }
        b.btnDeleteAll.setOnClickListener { confirmDeleteAll() }

        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(s: CharSequence?, x: Int, y: Int, z: Int) {
                loadLandmarks(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadLandmarks("")
    }

    private fun loadLandmarks(query: String) {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val list = WikiLandmarkHelper.search(this@LandmarkListActivity, query)
            adapter.submitList(list)
            b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            b.tvCount.text = "共 ${list.size} 筆"
        }
    }

    private fun confirmDeleteOne(landmark: LandmarkEntity) {
        AlertDialog.Builder(this, R.style.AlertDialogDark)
            .setTitle("刪除地標")
            .setMessage("確定要刪除「${landmark.name}」嗎？")
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    WikiLandmarkHelper.deleteById(this@LandmarkListActivity, landmark.id)
                    Toast.makeText(this@LandmarkListActivity, "已刪除：${landmark.name}", Toast.LENGTH_SHORT).show()
                    loadLandmarks(b.etSearch.text?.toString() ?: "")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this, R.style.AlertDialogDark)
            .setTitle("清空地標資料庫")
            .setMessage("確定要刪除全部地標嗎？此動作無法復原，需重新從 Wikipedia 抓取。")
            .setPositiveButton("全部刪除") { _, _ ->
                lifecycleScope.launch {
                    WikiLandmarkHelper.deleteAll(this@LandmarkListActivity)
                    Toast.makeText(this@LandmarkListActivity, "已清空地標資料庫", Toast.LENGTH_SHORT).show()
                    loadLandmarks("")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
