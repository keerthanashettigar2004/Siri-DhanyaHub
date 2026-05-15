package com.example.siri_dhanyahub

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import okhttp3.*
import java.io.IOException
import com.bumptech.glide.Glide
// AI IMPORTS
import com.google.ai.client.generativeai.GenerativeModel
import com.google.firebase.Firebase
import kotlinx.coroutines.launch
// FIREBASE IMPORTS
import com.google.firebase.database.PropertyName
import com.google.firebase.database.*
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
// --- DATA MODELS ---
data class MandiPrice(val city: String, val crop: String, val price: String, val range: String)
data class MandiResponse(val records: List<Record>)
data class Record(val market: String, val commodity: String, val modal_price: String, val arrival_date: String)
data class RecipeResponse(val results: List<OnlineRecipe>)
data class OnlineRecipe(val id: Int, val title: String, val image: String, val sourceUrl: String?)

// NEW: Farmer Data Model for Firebase
data class FarmerFPO(
    val name: String? = "",
    val district: String? = "",
    val crops: String? = "",
    val contact: String? = ""
)
data class HealthTip(val millet: String, val benefit: String)
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private val client = OkHttpClient()
    private lateinit var database: DatabaseReference // Firebase Reference

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. Initialize Firebase
        database = FirebaseDatabase.getInstance().reference

        // 2. Initialize Views
        recyclerView = findViewById(R.id.mandiRecyclerView)
        val recipeLayout = findViewById<LinearLayout>(R.id.recipeLayout)
        val directLayout = findViewById<View>(R.id.directLayout)
        val healthLayout = findViewById<LinearLayout>(R.id.healthLayout)
        val headerTitle = findViewById<TextView>(R.id.headerTitle)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)

        // AI Views
        val aiResponseText = findViewById<TextView>(R.id.aiResponseText)
        val etAiQuery = findViewById<EditText>(R.id.etAiQuery)
        val btnAskAi = findViewById<Button>(R.id.btnAskAi)

        // --- NEW: HEALTH TIPS SECTION START ---
        val healthTipsRv = findViewById<RecyclerView>(R.id.healthTipsRecyclerView)
        healthTipsRv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        val tipsList = listOf(
            HealthTip("Ragi", "Calcium rich - Great for bones and teeth."),
            HealthTip("Jowar", "Gluten-free & high fiber - Good for heart health."),
            HealthTip("Bajra", "High in Iron - Prevents anemia and boosts energy."),
            HealthTip("Navane", "Low Glycemic Index - Perfect for managing diabetes."),
            HealthTip("Sajje", "Natural coolant - Helps in detoxifying the body.")
        )
        healthTipsRv.adapter = HealthTipAdapter(tipsList)
        // --- NEW: HEALTH TIPS SECTION END ---

        recyclerView.layoutManager = LinearLayoutManager(this)

        // 3. Initialize Gemini AI
        val generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = "AIzaSyBtAEVmvR6B1eJjXyqct0c0pzano7JbJtI"
        )

        showFallbackMandiData()
        fetchLiveMandiPrices()

        // 4. AI Button Logic
        btnAskAi.setOnClickListener {
            val query = etAiQuery.text.toString()
            if (query.isNotEmpty()) {
                aiResponseText.text = "Siri-Dhanya AI is thinking..."
                etAiQuery.text.clear()
                lifecycleScope.launch {
                    try {
                        val response = generativeModel.generateContent(
                            "You are a professional nutritionist specializing in Siri-Dhanya (Indian Millets). " +
                                    "Explain the benefits of: $query. " +
                                    "Rules for your response: " +
                                    "1. Keep it professional but very easy for a common person to understand. " +
                                    "2. Use a maximum of 3 clear bullet points. " +
                                    "3. Keep the total length under 100 words. " +
                                    "4. Start with a 1-sentence summary of what this millet is best for."
                        )
                        aiResponseText.text = response.text
                    } catch (e: Exception) {
                        aiResponseText.text = "Error: ${e.localizedMessage}"
                        e.printStackTrace()
                    }
                }
            }
        }

        // 5. Navigation Logic
        bottomNav.setOnItemSelectedListener { item ->
            // Reset visibility
            recyclerView.visibility = View.GONE
            recipeLayout.visibility = View.GONE
            directLayout.visibility = View.GONE
            healthLayout.visibility = View.GONE

            when (item.itemId) {
                R.id.nav_monitor -> {

                    fetchLiveMandiPrices()
                    recyclerView.visibility = View.VISIBLE
                    headerTitle.text = "Mandi Price Pulse"
                    true
                }
                R.id.nav_cook -> {
                    fetchOnlineRecipes()
                    recipeLayout.visibility = View.VISIBLE
                    headerTitle.text = "Recipe Lab"
                    true
                }
                R.id.nav_direct -> {
                    recyclerView.adapter = FarmerAdapter(emptyList())
                    // Fetch from Firebase and show in the main RecyclerView
                    fetchFarmerDirectData()
                    recyclerView.visibility = View.VISIBLE
                    headerTitle.text = "Farmer Direct"
                    true
                }
                R.id.nav_health -> {
                    healthLayout.visibility = View.VISIBLE
                    headerTitle.text = "Siri-Dhanya AI"
                    true
                }
                else -> false
            }
        }
    }

    // --- FIREBASE LOGIC ---
    private fun fetchFarmerDirectData() {
        database.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val farmerList = mutableListOf<FarmerFPO>()
                Toast.makeText(this@MainActivity, "Found ${snapshot.childrenCount} farmers", Toast.LENGTH_SHORT).show()
                for (data in snapshot.children) {
                    val farmer = data.getValue(FarmerFPO::class.java)
                    farmer?.let { farmerList.add(it) }
                }
                runOnUiThread {
                    // Set the Farmer Adapter to the RecyclerView
                    recyclerView.adapter = FarmerAdapter(farmerList)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Error: ${error.message}")
                Toast.makeText(this@MainActivity, "Failed to load farmers", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // --- MANDI API LOGIC ---
    private fun showFallbackMandiData() {
        val fallback = listOf(
            MandiPrice("Bengaluru", "RAGI", "₹3,850", "Updated: Today (Simulated)"),
            MandiPrice("Mysuru", "NAVANE", "₹5,200", "Updated: Today (Simulated)"),
            MandiPrice("Tumakuru", "SAJJE", "₹2,950", "Updated: Today (Simulated)"),
            MandiPrice("Davanagere", "JOWAR", "₹4,100", "Updated: Today (Simulated)"),
            MandiPrice("Hubballi", "BARAGU", "₹4,800", "Updated: Today (Simulated)"),
            MandiPrice("Belagavi", "SAME", "₹5,500", "Updated: Today (Simulated)"),

            MandiPrice("Ballari", "HARAKA", "₹6,100", "Updated: Today (Simulated)"),
            MandiPrice("Kalaburagi", "OODALU", "₹5,900", "Updated: Today (Simulated)"),
            MandiPrice("Raichur", "RAGI (Organic)", "₹4,200", "Updated: Today (Simulated)"),
            MandiPrice("Shivamogga", "NAVANE (Grade A)", "₹5,450", "Updated: Today (Simulated)")
        )
        recyclerView.adapter = MandiAdapter(fallback)
    }

    private fun fetchLiveMandiPrices() {
        val myApiKey = "579b464db66ec23bdd000001492962dfd0ac4d3045b360722f36995f"
        val url = "https://api.data.gov.in/resource/9ef273d6-b1da-4575-bb35-23a07f1a9941?api-key=$myApiKey&format=json&limit=100"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    // If there is NO internet, this will show:
                    Toast.makeText(this@MainActivity, "Connection Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()

                if (!response.isSuccessful) {
                    runOnUiThread {
                        // 403 means your API key is wrong or not active yet
                        Toast.makeText(this@MainActivity, "API Error Code: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                if (body != null) {
                    try {
                        val governmentData = Gson().fromJson(body, MandiResponse::class.java)
                        if (governmentData.records != null && governmentData.records.isNotEmpty()) {
                            val mappedList = governmentData.records.map {
                                MandiPrice(it.market, it.commodity, "₹${it.modal_price}", "Arrival: ${it.arrival_date}")
                            }
                            runOnUiThread {
                                recyclerView.adapter = MandiAdapter(mappedList)
                                Toast.makeText(this@MainActivity, "Live Prices Loaded!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "No records found in API", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Data Mapping Error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
    }
    // --- RECIPE API LOGIC ---
    private fun fetchOnlineRecipes() {
        val spoonApiKey = "73074be9c5f143d085b9612d778d70da"
        val url = "https://api.spoonacular.com/recipes/complexSearch?query=millet&number=10&addRecipeInformation=true&apiKey=$spoonApiKey"
        val request = Request.Builder().url(url).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { e.printStackTrace() }
            override fun onResponse(call: Call, response: Response) {
                val bodyString = response.body?.string()
                if (bodyString != null) {
                    val data = Gson().fromJson(bodyString, RecipeResponse::class.java)
                    runOnUiThread {
                        val rv = findViewById<RecyclerView>(R.id.recipeRecyclerView)
                        rv.layoutManager = LinearLayoutManager(this@MainActivity)
                        rv.adapter = RecipeAdapter(data.results)
                    }
                }
            }
        })
    }
}

// --- ADAPTERS ---

// 1. Mandi Price Adapter
class MandiAdapter(private val list: List<MandiPrice>) : RecyclerView.Adapter<MandiAdapter.MandiViewHolder>() {
    class MandiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCity: TextView = view.findViewById(R.id.tvCity)
        val tvCrop: TextView = view.findViewById(R.id.tvCrop)
        val tvPrice: TextView = view.findViewById(R.id.tvPrice)
        val tvRange: TextView = view.findViewById(R.id.tvRange)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MandiViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_mandi_price, parent, false))
    override fun onBindViewHolder(holder: MandiViewHolder, position: Int) {
        val item = list[position]
        holder.tvCity.text = item.city
        holder.tvCrop.text = item.crop
        holder.tvPrice.text = item.price
        holder.tvRange.text = item.range
    }
    override fun getItemCount() = list.size
}

// 2. Recipe Adapter
class RecipeAdapter(private val list: List<OnlineRecipe>) : RecyclerView.Adapter<RecipeAdapter.ViewHolder>() {
    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val img: android.widget.ImageView = v.findViewById(R.id.recipeImage)
        val title: TextView = v.findViewById(R.id.recipeTitle)
    }
    override fun onCreateViewHolder(p: ViewGroup, t: Int) = ViewHolder(LayoutInflater.from(p.context).inflate(R.layout.item_recipe, p, false))
    override fun onBindViewHolder(h: ViewHolder, p: Int) {
        val item = list[p]
        h.title.text = item.title
        Glide.with(h.itemView.context).load(item.image).into(h.img)
        h.itemView.setOnClickListener {
            val recipeUrl = item.sourceUrl
            if (!recipeUrl.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(recipeUrl))
                h.itemView.context.startActivity(intent)
            } else {
                Toast.makeText(h.itemView.context, "Recipe link not available", Toast.LENGTH_SHORT).show()
            }
        }
    }
    override fun getItemCount() = list.size
}

// 3. NEW: Farmer Adapter for Firebase Data
class FarmerAdapter(private val list: List<FarmerFPO>) : RecyclerView.Adapter<FarmerAdapter.FarmerViewHolder>() {
    class FarmerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.fpoName)
        val tvLoc: TextView = view.findViewById(R.id.fpoLocation)
        val tvCrops: TextView = view.findViewById(R.id.fpoCrops) // ADD THIS LINE
        val btnRequest: Button = view.findViewById(R.id.btnRequestSupply)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FarmerViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_farmer, parent, false)
        return FarmerViewHolder(view)
    }

    override fun onBindViewHolder(holder: FarmerViewHolder, position: Int) {
        val item = list[position]

        holder.tvName.text = item.name ?: "Unknown Farmer"
        holder.tvLoc.text = item.district ?: "District Missing"

        // ADD THIS LINE TO SHOW THE CROPS:
        holder.tvCrops.text = "Crops: ${item.crops ?: "Not specified"}"

        holder.btnRequest.setOnClickListener {
            val phone = item.contact
            if (!phone.isNullOrEmpty()) {
                val intent = Intent(Intent.ACTION_DIAL)
                intent.data = Uri.parse("tel:$phone")
                holder.itemView.context.startActivity(intent)
            }
        }
    }

    override fun getItemCount() = list.size
}
class HealthTipAdapter(private val tips: List<HealthTip>) : RecyclerView.Adapter<HealthTipAdapter.TipViewHolder>() {
    class TipViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tipMilletName)
        val desc: TextView = v.findViewById(R.id.tipDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        TipViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_health_tip, parent, false))

    override fun onBindViewHolder(holder: TipViewHolder, position: Int) {
        val tip = tips[position]
        holder.name.text = tip.millet
        holder.desc.text = tip.benefit
    }

    override fun getItemCount() = tips.size
}