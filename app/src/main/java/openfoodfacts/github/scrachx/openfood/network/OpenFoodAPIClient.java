package openfoodfacts.github.scrachx.openfood.network;

import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;
import com.afollestad.materialdialogs.MaterialDialog;
import com.fasterxml.jackson.databind.JsonNode;
import com.firebase.jobdispatcher.JobParameters;
import io.reactivex.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import openfoodfacts.github.scrachx.openfood.BuildConfig;
import openfoodfacts.github.scrachx.openfood.R;
import openfoodfacts.github.scrachx.openfood.jobs.SavedProductUploadJob;
import openfoodfacts.github.scrachx.openfood.models.*;
import openfoodfacts.github.scrachx.openfood.utils.ImageUploadListener;
import openfoodfacts.github.scrachx.openfood.utils.LocaleHelper;
import openfoodfacts.github.scrachx.openfood.utils.Utils;
import openfoodfacts.github.scrachx.openfood.views.AddProductActivity;
import openfoodfacts.github.scrachx.openfood.views.Installation;
import openfoodfacts.github.scrachx.openfood.views.OFFApplication;
import openfoodfacts.github.scrachx.openfood.views.product.ProductActivity;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.*;

import static openfoodfacts.github.scrachx.openfood.models.ProductImageField.*;
import static openfoodfacts.github.scrachx.openfood.network.OpenFoodAPIService.PRODUCT_API_COMMENT;

public class OpenFoodAPIClient {
    public static final String TEXT_PLAIN = "text/plain";

    private HistoryProductDao mHistoryProductDao;
    private ToUploadProductDao mToUploadProductDao;
    private OfflineUploadingTask task = new OfflineUploadingTask();
    private static final JacksonConverterFactory jacksonConverterFactory = JacksonConverterFactory.create();
    private DaoSession daoSession;
    private static OkHttpClient httpClient = Utils.HttpClientBuilder();
    private final OpenFoodAPIService apiService;
    private Context mActivity;

    public OpenFoodAPIClient(Activity activity) {
        this(BuildConfig.HOST);
        mHistoryProductDao = Utils.getAppDaoSession(activity).getHistoryProductDao();
        mToUploadProductDao = Utils.getAppDaoSession(activity).getToUploadProductDao();
        mActivity = activity;
    }

    //used to upload in background
    public OpenFoodAPIClient(Context context) {
        this(BuildConfig.HOST);
        daoSession = Utils.getDaoSession(context);
        mToUploadProductDao = daoSession.getToUploadProductDao();
    }

    public OpenFoodAPIClient(Activity activity, String url) {
        this(url);
        mHistoryProductDao = Utils.getAppDaoSession(activity).getHistoryProductDao();
        mToUploadProductDao = Utils.getAppDaoSession(activity).getToUploadProductDao();
    }

    private OpenFoodAPIClient(String apiUrl) {
        apiService = new Retrofit.Builder()
            .baseUrl(apiUrl)
            .client(httpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(jacksonConverterFactory)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.createWithScheduler(Schedulers.io()))
            .build()
            .create(OpenFoodAPIService.class);
    }

    public static String getCommentToUpload(String login) {
        String comment = PRODUCT_API_COMMENT + " " + Utils.getVersionName(OFFApplication.getInstance());
        if (login.isEmpty()) {
            comment += " ( Added by " + Installation.id(OFFApplication.getInstance()) + " )";
        }
        return comment;
    }

    public static RequestBody createImageRequest(File image) {
        return RequestBody.create(MediaType.parse("image/*"), image);
    }

    /**
     * Open the product activity if the barcode exist.
     * Also add it in the history if the product exist.
     *
     * @param barcode product barcode
     * @param activity
     */
    public void getProduct(final String barcode, final Activity activity) {
        apiService.getFullProductByBarcode(barcode, Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH)).enqueue(new Callback<State>() {
            @Override
            public void onResponse(@NonNull Call<State> call, @NonNull Response<State> response) {

                if (activity == null || activity.isFinishing()) {
                    return;
                }

                final State s = response.body();
                if (s.getStatus() == 0) {
                    productNotFoundDialogBuilder(activity, barcode)
                        .onNegative((dialog, which) -> activity.onBackPressed())
                        .show();
                } else {
                    new HistoryTask().doInBackground(s.getProduct());
                    Intent intent = new Intent(activity, ProductActivity.class);
                    Bundle bundle = new Bundle();
                    String langCode = LocaleHelper.getLanguage(activity.getApplicationContext());
                    String[] fieldsArray = activity.getResources().getStringArray(R.array.fields_array);
                    ArrayList<String> fieldsList = new ArrayList<>();
                    for (int i = 0; i < fieldsArray.length; i++) {
                        if (!langCode.equals("en")) {
                            fieldsList.add(fieldsArray[i] + "_" + langCode);
                        }
                        fieldsList.add(fieldsArray[i] + "_en");
                    }
                    getFieldByLanguage(barcode, fieldsList, ((((value, result) -> {

                        Product product = s.getProduct();
                        for (HashMap.Entry<String, String> fieldEntry : result.entrySet()) {
                            if (fieldEntry.getValue() != null) {
                                String fieldValue = fieldEntry.getValue();
                                if (fieldValue != null) {
                                    fieldValue = fieldValue.replace("\"", "");
                                    product.setAdditionalProperty(fieldEntry.getKey(), fieldValue);
                                }
                            }
                        }
                        s.setProduct(product);
                        bundle.putSerializable("state", s);
                        intent.putExtras(bundle);
                        activity.startActivity(intent);
                    }))));
                }
            }

            @Override
            public void onFailure(@NonNull Call<State> call, @NonNull Throwable t) {

                if (activity == null || activity.isFinishing()) {
                    return;
                }

                productNotFoundDialogBuilder(activity, barcode)
                    .show();
            }
        });
    }

    public MaterialDialog.Builder productNotFoundDialogBuilder(Activity activity, String barcode) {
        return new MaterialDialog.Builder(activity)
            .title(R.string.txtDialogsTitle)
            .content(R.string.txtDialogsContent)
            .positiveText(R.string.txtYes)
            .negativeText(R.string.txtNo)
            .onPositive((dialog, which) -> {
                if (!activity.isFinishing()) {
                    Intent intent = new Intent(activity, AddProductActivity.class);
                    State st = new State();
                    Product pd = new Product();
                    pd.setCode(barcode);
                    st.setProduct(pd);
                    intent.putExtra("state", st);
                    activity.startActivity(intent);
                    activity.finish();
                }
            });
    }

    public void getFieldByLanguage(String barcode, ArrayList<String> fields, final OnFieldByLanguageCallback fieldByLanguageCallback) {
        StringBuilder fieldQuery = new StringBuilder();
        for (String s : fields) {
            fieldQuery.append(s);
            fieldQuery.append(",");
        }
        apiService.getFieldByLangCode(barcode, fieldQuery.toString()).enqueue(new Callback<JsonNode>() {
            @Override
            public void onResponse(Call<JsonNode> call, Response<JsonNode> response) {
                JsonNode responseNode = response.body();
                if (responseNode != null) {
                    HashMap<String, String> resultMap = new HashMap<>();
                    final JsonNode product = responseNode.findValue("product");
                    if (product != null) {
                        for (String field : fields) {
                            final JsonNode productValue = product.findValue(field);
                            if (productValue != null &&
                                (!productValue.toString().equals(""))) {
                                String value = productValue.toString();
                                resultMap.put(field, value);
                            } else {
                                resultMap.put(field, null);
                            }
                        }
                    }
                    fieldByLanguageCallback.onFieldByLanguageResponse(true, resultMap);
                }
            }

            @Override
            public void onFailure(Call<JsonNode> call, Throwable t) {
                fieldByLanguageCallback.onFieldByLanguageResponse(false, null);
            }
        });
    }

    public void getIngredients(String barcode, final OnIngredientListCallback ingredientListCallback) {

        apiService.getIngredientsByBarcode(barcode).enqueue(new Callback<JsonNode>() {
            @Override
            public void onResponse(@NonNull Call<JsonNode> call, Response<JsonNode> response) {
                final JsonNode node = response.body();
                if(node==null){
                    return;
                }
                final JsonNode ingredientsJsonNode = node.findValue("ingredients");
                if (ingredientsJsonNode != null) {
                    ArrayList<ProductIngredient> productIngredients = new ArrayList<>();
                    final int nbIngredient = ingredientsJsonNode.size();
                    for (int i = 0; i < nbIngredient; i++) {
                        ProductIngredient productIngredient = new ProductIngredient();
                        final JsonNode ingredient = ingredientsJsonNode.get(i);
                        if (ingredient != null) {
                            productIngredient.setId(ingredient.findValue("id").toString());
                            productIngredient.setText(ingredient.findValue("text").toString());
                            productIngredient.setRank(Long.valueOf(ingredient.findValue("rank").toString()));
                            productIngredients.add(productIngredient);
                        }
                    }
                    ingredientListCallback.onIngredientListResponse(true, productIngredients);
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonNode> call, Throwable t) {
                ingredientListCallback.onIngredientListResponse(false, null);
            }
        });
    }

    public void searchProduct(final String name, final int page, final Activity activity, final OnProductsCallback productsCallback) {
        String productNameLocale = getLocaleProductNameField();
        String fields = "selected_images,image_small_url,product_name,brands,quantity,code,nutrition_grade_fr," + productNameLocale;

        apiService.searchProductByName(fields, name, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (!response.isSuccessful()) {
                    productsCallback.onProductsResponse(false, null, -1);
                    return;
                }

                Search s = response.body();
                if (s == null || Integer.valueOf(s.getCount()) == 0) {
                    productsCallback.onProductsResponse(false, null, -2);
                } else {
                    productsCallback.onProductsResponse(true, s, Integer.parseInt(s.getCount()));
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                if (activity != null && !activity.isFinishing()) {
                    Toast.makeText(activity, activity.getString(R.string.errorWeb), Toast.LENGTH_LONG).show();
                }
                productsCallback.onProductsResponse(false, null, -1);
            }
        });
    }

    public static String getLocaleProductNameField() {
        String locale = LocaleHelper.getLanguage(OFFApplication.getInstance());
        return "product_name_" + locale;
    }

    public void onResponseCallForPostFunction(Call<State> call, Response<State> response, Context activity, final OnProductSentCallback productSentCallback, SendProduct product) {
        postImages(response, activity, productSentCallback, product);
    }

    /**
     * @return This api service gets products of provided brand.
     */
    public OpenFoodAPIService getAPIService() {
        return apiService;
    }

    public void getBrand(final String brand, final int page, final OnBrandCallback onBrandCallback) {

        apiService.getProductByBrands(brand, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(Call<Search> call, Response<Search> response) {
                onBrandCallback.onBrandResponse(true, response.body());
            }

            @Override
            public void onFailure(Call<Search> call, Throwable t) {
                onBrandCallback.onBrandResponse(false, null);
            }
        });
    }

    public interface OnImagesCallback {
        void onImageResponse(boolean value, String response);
    }

    public void getImages(String barcode, OnImagesCallback onImagesCallback) {

        apiService.getProductImages(barcode).enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {

                onImagesCallback.onImageResponse(true, response.body());
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {

                onImagesCallback.onImageResponse(false, null);
            }
        });
    }

    /**
     * This method is used to upload products.
     * Conditional statements in this method ensures that data which is being sent on server is correct
     * and if the product is already present with more information then the server doesn't assume to delete that
     * and write new product's data over that.
     */
    public void post(final Context activity, final SendProduct product, final OnProductSentCallback productSentCallback) {
        ProgressDialog dialog = new ProgressDialog(activity, ProgressDialog.STYLE_SPINNER);
        dialog.setIndeterminate(true);
        dialog.setMessage(activity.getString(R.string.toastSending));

        if (product.getName().equals("") && product.getBrands().equals("") && product.getQuantity() == null) {
            apiService.saveProductWithoutNameBrandsAndQuantity(product.getBarcode(), product.getLang(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createProductCallBack(activity, product, productSentCallback, dialog));
        } else if (product.getName().equals("") && product.getBrands().equals("")) {
            apiService
                .saveProductWithoutNameAndBrands(product.getBarcode(), product.getLang(), product.getQuantity(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createProductCallBack(activity, product, productSentCallback, dialog));
        } else if (product.getName().equals("") && product.getQuantity() == null) {
            apiService
                .saveProductWithoutNameAndQuantity(product.getBarcode(), product.getLang(), product.getBrands(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createProductCallBack(activity, product, productSentCallback, dialog));
        } else if (product.getBrands().equals("") && product.getQuantity() == null) {
            apiService
                .saveProductWithoutBrandsAndQuantity(product.getBarcode(), product.getLang(), product.getName(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createProductCallBack(activity, product, productSentCallback, dialog));
        } else {
            apiService.saveProduct(product.getBarcode(), product.getLang(), product.getName(), product.getBrands(), product.getQuantity(), product
                .getUserId(), product.getPassword(), PRODUCT_API_COMMENT).enqueue(createProductCallBack(activity, product, productSentCallback, dialog));
        }
    }

    private Callback<State> createProductCallBack(Context activity, SendProduct product, OnProductSentCallback productSentCallback, Dialog dialog) {
        return new Callback<State>() {
            @Override
            public void onResponse(Call<State> call, Response<State> response) {
                onResponseCallForPostFunction(call, response, activity, productSentCallback, product);
                dialog.dismiss();
            }

            @Override
            public void onFailure(Call<State> call, Throwable t) {
                productSentCallback.onProductSentResponse(false);
                dialog.dismiss();
            }
        };
    }

    public void postImg(final Context context, final ProductImage image, ImageUploadListener imageUploadListener) {
        apiService.saveImage(getUploadableMap(image, context))
            .enqueue(new Callback<JsonNode>() {
                @Override
                public void onResponse(@NonNull Call<JsonNode> call, @NonNull Response<JsonNode> response) {
                    Log.d("onResponse", response.toString());
                    if (!response.isSuccessful()) {
                        ToUploadProduct product = new ToUploadProduct(image.getBarcode(), image.getFilePath(), image.getImageField().toString());
                        mToUploadProductDao.insertOrReplace(product);
                        Toast.makeText(context, response.toString(), Toast.LENGTH_LONG).show();
                        if (imageUploadListener != null) {
                            imageUploadListener.onFailure(response.toString());
                        }
                        return;
                    }

                    JsonNode body = response.body();
                    Log.d("onResponse", body.toString());
                    if (imageUploadListener != null) {
                        imageUploadListener.onSuccess();
                    }
                    if (!body.isObject()) {
                    } else if (body.get("status").asText().contains("status not ok")) {
                        Toast.makeText(context, body.get("error").asText(), Toast.LENGTH_LONG).show();
                    } else {
                    }
                }

                @Override
                public void onFailure(@NonNull Call<JsonNode> call, @NonNull Throwable t) {
                    Log.d("onResponse", t.toString());
                    if (imageUploadListener != null) {
                        imageUploadListener.onFailure(context.getString(R.string.uploadLater));
                    }
                    ToUploadProduct product = new ToUploadProduct(image.getBarcode(), image.getFilePath(), image.getImageField().toString());
                    mToUploadProductDao.insertOrReplace(product);
                    Toast.makeText(context, context.getString(R.string.uploadLater), Toast.LENGTH_LONG).show();
                }
            });
    }

    private Map<String, RequestBody> getUploadableMap(ProductImage image, Context context) {
        final String lang = LocaleHelper.getLanguage(context);

        Map<String, RequestBody> imgMap = new HashMap<>();
        imgMap.put("code", image.getCode());
        imgMap.put("imagefield", image.getField());
        if (image.getImguploadFront() != null) {
            imgMap.put("imgupload_front\"; filename=\"front_" + lang + ".png\"", image.getImguploadFront());
        }
        if (image.getImguploadIngredients() != null) {
            imgMap.put("imgupload_ingredients\"; filename=\"ingredients_" + lang + ".png\"", image.getImguploadIngredients());
        }
        if (image.getImguploadNutrition() != null) {
            imgMap.put("imgupload_nutrition\"; filename=\"nutrition_" + lang + ".png\"", image.getImguploadNutrition());
        }
        if (image.getImguploadOther() != null) {
            imgMap.put("imgupload_other\"; filename=\"other_" + lang + ".png\"", image.getImguploadOther());
        }

        // Attribute the upload to the connected user
        final SharedPreferences settings = context.getSharedPreferences("login", 0);
        fillWithUserLoginInfo(imgMap, settings);
        return imgMap;
    }

    public static String fillWithUserLoginInfo(Map<String, RequestBody> imgMap, SharedPreferences settings) {
        final String login = settings.getString("user", "");
        final String password = settings.getString("pass", "");

        if (!login.isEmpty() && !password.isEmpty()) {
            imgMap.put("user_id", RequestBody.create(MediaType.parse(TEXT_PLAIN), login));
            imgMap.put("password", RequestBody.create(MediaType.parse(TEXT_PLAIN), password));
        }
        imgMap.put("comment", RequestBody.create(MediaType.parse(TEXT_PLAIN), getCommentToUpload(login)));
        return login;
    }

    public interface OnProductsCallback {
        void onProductsResponse(boolean isOk, Search searchResponse, int countProducts);
    }

    public interface OnAllergensCallback {
        void onAllergensResponse(boolean value, Search allergen);
    }

    public interface OnBrandCallback {
        void onBrandResponse(boolean value, Search brand);
    }

    public interface OnStoreCallback {
        void onStoreResponse(boolean value, Search store);
    }

    public interface OnPackagingCallback {
        void onPackagingResponse(boolean value, Search packaging);
    }

    public interface OnAdditiveCallback {
        void onAdditiveResponse(boolean value, Search brand);
    }

    public interface OnProductSentCallback {
        void onProductSentResponse(boolean value);
    }

    public interface onCountryCallback {
        void onCountryResponse(boolean value, Search country);
    }

    public interface onLabelCallback {
        void onLabelResponse(boolean value, Search label);
    }

    public interface onCategoryCallback {
        void onCategoryResponse(boolean value, Search category);
    }

    public interface onContributorCallback {
        void onContributorResponse(boolean value, Search contributor);
    }

    public interface OnIngredientListCallback {
        void onIngredientListResponse(boolean value, ArrayList<ProductIngredient> productIngredients);
    }

    public interface OnFieldByLanguageCallback {
        void onFieldByLanguageResponse(boolean value, HashMap<String, String> result);
    }

    /**
     * Create an history product asynchronously
     */
    private class HistoryTask extends AsyncTask<Product, Void, Void> {
        @Override
        protected Void doInBackground(Product... products) {
            if (ArrayUtils.isNotEmpty(products)) {
                Product product = products[0];
                addToHistory(mHistoryProductDao, product);
            }
            return null;
        }
    }

    public static void addToHistory(HistoryProductDao mHistoryProductDao, Product product) {
        List<HistoryProduct> historyProducts = mHistoryProductDao.queryBuilder().where(HistoryProductDao.Properties.Barcode.eq(product.getCode())).list();
        HistoryProduct hp;
        if (historyProducts.size() == 1) {
            hp = historyProducts.get(0);
            hp.setLastSeen(new Date());
        } else {
            hp = new HistoryProduct(product.getProductName(), product.getBrands(), product.getImageSmallUrl(LocaleHelper.getLanguage(OFFApplication.getInstance())),
                product.getCode(), product
                .getQuantity(), product.getNutritionGradeFr());
        }
        mHistoryProductDao.insertOrReplace(hp);
    }

    public void uploadOfflineImages(Context context, boolean cancel, JobParameters job, SavedProductUploadJob service) {
        if (!cancel) {
            task.job = job;
            task.service = new WeakReference<>(service);
            task.execute(context);
        } else {
            task.cancel(true);
        }
    }

    public class OfflineUploadingTask extends AsyncTask<Context, Void, Void> {
        JobParameters job;
        WeakReference<SavedProductUploadJob> service;

        @Override
        protected Void doInBackground(Context... context) {
            List<ToUploadProduct> toUploadProductList = mToUploadProductDao.queryBuilder().where(ToUploadProductDao.Properties.Uploaded.eq(false)
            ).list();
            int totalSize = toUploadProductList.size();
            for (int i = 0; i < totalSize; i++) {
                ToUploadProduct uploadProduct = toUploadProductList.get(i);
                File imageFile;
                try {
                    imageFile = new File(uploadProduct.getImageFilePath());
                } catch (Exception e) {
                    Log.e("OfflineUploadingTask", "doInBackground", e);
                    continue;
                }
                ProductImage productImage = new ProductImage(uploadProduct.getBarcode(),
                    uploadProduct.getProductField(), imageFile);

                apiService.saveImage(getUploadableMap(productImage, context[0]))
                    .enqueue(new Callback<JsonNode>() {
                        @Override
                        public void onResponse(@NonNull Call<JsonNode> call, @NonNull Response<JsonNode> response) {
                            if (!response.isSuccessful()) {
                                Toast.makeText(context[0], response.toString(), Toast.LENGTH_LONG).show();
                                return;
                            }

                            JsonNode body = response.body();
                            if (body != null) {
                                Log.d("onResponse", body.toString());
                                if (!body.isObject()) {

                                } else if (body.get("status").asText().contains("status not ok")) {
                                    mToUploadProductDao.delete(uploadProduct);
                                } else {
                                    mToUploadProductDao.delete(uploadProduct);
                                }
                            }
                        }

                        @Override
                        public void onFailure(@NonNull Call<JsonNode> call, @NonNull Throwable t) {

                        }
                    });
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            Log.d("serviceValue", service.get().toString());
            service.get().jobFinished(job, false);
        }
    }

    public void getProductsByBrand(final String brand, final int page, final OnBrandCallback onBrandCallback) {

        apiService.getProductByBrands(brand, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onBrandCallback.onBrandResponse(true, response.body());
                } else {
                    onBrandCallback.onBrandResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onBrandCallback.onBrandResponse(false, null);
            }
        });
    }

    public interface OnEditImageCallback {
        void onEditResponse(boolean value, String response);
    }

    public void editImage(String code, Map<String, String> imgMap, OnEditImageCallback onEditImageCallback) {
        apiService.editImages(code, imgMap).enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {

                onEditImageCallback.onEditResponse(true, response.body());
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                onEditImageCallback.onEditResponse(false, null);
            }
        });
    }

    public void getProductsByPackaging(final String packaging, final int page, final OnPackagingCallback onPackagingCallback) {

        apiService.getProductByPackaging(packaging, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onPackagingCallback.onPackagingResponse(true, response.body());
                } else {
                    onPackagingCallback.onPackagingResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onPackagingCallback.onPackagingResponse(false, null);
            }
        });
    }

    public void syncOldHistory() {
        new SyncOldHistoryTask().execute();
    }

    public class SyncOldHistoryTask extends AsyncTask<Void, Void, Void> {
        boolean success = true;

        @Override
        protected Void doInBackground(Void... voids) {
            List<HistoryProduct> historyProducts = mHistoryProductDao.loadAll();
            int size = historyProducts.size();
            for (int i = 0; i < size; i++) {
                HistoryProduct historyProduct = historyProducts.get(i);
                apiService.getShortProductByBarcode(historyProduct.getBarcode(), Utils.getUserAgent(Utils.HEADER_USER_AGENT_SEARCH)).enqueue(new Callback<State>() {
                    @Override
                    public void onResponse(@NonNull Call<State> call, @NonNull Response<State> response) {
                        final State s = response.body();

                        if (s != null && s.getStatus() != 0) {
                            Product product = s.getProduct();
                            HistoryProduct hp = new HistoryProduct(product.getProductName(), product.getBrands(),
                                product.getImageSmallUrl(LocaleHelper.getLanguage(OFFApplication.getInstance())),
                                product.getCode(), product.getQuantity(), product.getNutritionGradeFr());
                            Log.d("syncOldHistory", hp.toString());

                            hp.setLastSeen(historyProduct.getLastSeen());
                            mHistoryProductDao.insertOrReplace(hp);
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call<State> call, @NonNull Throwable t) {
                        success = false;
                    }
                });
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            super.onPostExecute(aVoid);
            if (success) {
                mActivity.getSharedPreferences("prefs", 0).edit().putBoolean("is_old_history_data_synced", true).apply();
            }
        }
    }

    public void getProductsByStore(final String store, final int page, final OnStoreCallback onStoreCallback) {
        apiService.getProductByStores(store, page).enqueue(createStoreCallback(onStoreCallback));
    }

    public void getProductsByOrigin(final String origin, final int page, final OnStoreCallback onStoreCallback) {
        apiService.getProductsByOrigin(origin, page).enqueue(createStoreCallback(onStoreCallback));
    }

    public Callback<Search> createStoreCallback(OnStoreCallback onStoreCallback) {
        return new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onStoreCallback.onStoreResponse(true, response.body());
                } else {
                    onStoreCallback.onStoreResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onStoreCallback.onStoreResponse(false, null);
            }
        };
    }

    public void getProductsByManufacturingPlace(final String manufacturingPlace, final int page, final OnStoreCallback onStoreCallback) {
        apiService.getProductsByManufacturingPlace(manufacturingPlace, page).enqueue(createStoreCallback(onStoreCallback));
    }

    public void getProductsByCountry(String country, final int page, final onCountryCallback onCountryCallback) {
        apiService.getProductsByCountry(country, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onCountryCallback.onCountryResponse(true, response.body());
                } else {
                    onCountryCallback.onCountryResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {

                onCountryCallback.onCountryResponse(false, null);
            }
        });
    }

    public void getProductsByAdditive(final String additive, final int page, final OnAdditiveCallback onAdditiveCallback) {

        apiService.getProductsByAdditive(additive, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onAdditiveCallback.onAdditiveResponse(true, response.body());
                } else {
                    onAdditiveCallback.onAdditiveResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onAdditiveCallback.onAdditiveResponse(false, null);
            }
        });
    }

    public void getProductsByAllergen(final String allergen, final int page, final OnAllergensCallback onAllergensCallback) {
        apiService.getProductsByAllergen(allergen, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(Call<Search> call, Response<Search> response) {
                if (response.isSuccessful()) {
                    onAllergensCallback.onAllergensResponse(true, response.body());
                } else {
                    onAllergensCallback.onAllergensResponse(false, null);
                }
            }

            @Override
            public void onFailure(Call<Search> call, Throwable t) {
                onAllergensCallback.onAllergensResponse(false, null);
            }
        });
    }

    public void getProductsByLabel(String label, final int page, final onLabelCallback onLabelCallback) {
        apiService.getProductByLabel(label, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onLabelCallback.onLabelResponse(true, response.body());
                } else {
                    onLabelCallback.onLabelResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onLabelCallback.onLabelResponse(false, null);
            }
        });
    }

    public void getProductsByCategory(String category, final int page, final onCategoryCallback onCategoryCallback) {
        apiService.getProductByCategory(category, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onCategoryCallback.onCategoryResponse(true, response.body());
                } else {
                    onCategoryCallback.onCategoryResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onCategoryCallback.onCategoryResponse(false, null);
            }
        });
    }

    public void getProductsByContributor(String contributor, final int page, final onContributorCallback onContributorCallback) {
        apiService.searchProductsByContributor(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    private Callback<Search> createCallback(onContributorCallback onContributorCallback) {
        return new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onContributorCallback.onContributorResponse(true, response.body());
                } else {
                    onContributorCallback.onContributorResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onContributorCallback.onContributorResponse(false, null);
            }
        };
    }

    public interface OnIncompleteCallback {
        void onIncompleteResponse(boolean value, Search incompleteProducts);
    }

    public void getIncompleteProducts(int page, OnIncompleteCallback onIncompleteCallback) {
        apiService.getIncompleteProducts(page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(Call<Search> call, Response<Search> response) {
                if (response.isSuccessful()) {
                    onIncompleteCallback.onIncompleteResponse(true, response.body());
                } else {
                    onIncompleteCallback.onIncompleteResponse(false, null);
                }
            }

            @Override
            public void onFailure(Call<Search> call, Throwable t) {

                onIncompleteCallback.onIncompleteResponse(false, null);
            }
        });
    }

    public void getToBeCompletedProductsByContributor(String contributor, final int page, final onContributorCallback onContributorCallback) {
        apiService.getToBeCompletedProductsByContributor(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getPicturesContributedProducts(String contributor, final int page, final onContributorCallback onContributorCallback) {
        apiService.getPicturesContributedProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getPicturesContributedIncompleteProducts(String contributor, final int page, final onContributorCallback onContributorCallback) {
        apiService.getPicturesContributedIncompleteProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getInfoAddedProducts(String contributor, final int page, final onContributorCallback onContributorCallback) {
        apiService.getInfoAddedProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public void getInfoAddedIncompleteProducts(String contributor, final int page, final onContributorCallback onContributorCallback) {
        apiService.getInfoAddedIncompleteProducts(contributor, page).enqueue(createCallback(onContributorCallback));
    }

    public interface onStateCallback {
        void onStateResponse(boolean value, Search state);
    }

    public void getProductsByStates(String state, final int page, final onStateCallback onStateCallback) {
        apiService.getProductsByState(state, page).enqueue(new Callback<Search>() {
            @Override
            public void onResponse(@NonNull Call<Search> call, @NonNull Response<Search> response) {
                if (response.isSuccessful()) {
                    onStateCallback.onStateResponse(true, response.body());
                } else {
                    onStateCallback.onStateResponse(false, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<Search> call, @NonNull Throwable t) {
                onStateCallback.onStateResponse(false, null);
            }
        });
    }

    /**
     * OnResponseCall for uploads through notifications
     */
    public void onResponseCallForNotificationPostFunction(Call<State> call, Response<State> response, Context context, final OnProductSentCallback productSentCallback,
                                                          SendProduct product) {
        postImages(response, context, productSentCallback, product);
    }

    private void postImages(Response<State> response, Context context, OnProductSentCallback productSentCallback, SendProduct product) {
        if (!response.isSuccessful() || response.body().getStatus() == 0) {

            productSentCallback.onProductSentResponse(false);
            return;
        }

        String imguploadFront = product.getImgupload_front();
        if (StringUtils.isNotEmpty(imguploadFront)) {
            postImg(context, new ProductImage(product.getBarcode(), FRONT, new File(imguploadFront)), null);
        }

        String imguploadIngredients = product.getImgupload_ingredients();
        if (StringUtils.isNotEmpty(imguploadIngredients)) {
            postImg(context, new ProductImage(product.getBarcode(), INGREDIENTS, new File(imguploadIngredients)), null);
        }

        String imguploadNutrition = product.getImgupload_nutrition();
        if (StringUtils.isNotBlank(imguploadNutrition)) {
            postImg(context, new ProductImage(product.getBarcode(), NUTRITION, new File(imguploadNutrition)), null);
        }

        productSentCallback.onProductSentResponse(true);
    }

    /**
     * Post method for upload through notification
     */
    public void postForNotification(final Context context, final SendProduct product, final OnProductSentCallback productSentCallback) {

        if (product.getName().equals("") && product.getBrands().equals("") && product.getQuantity() == null) {
            apiService.saveProductWithoutNameBrandsAndQuantity(product.getBarcode(), product.getLang(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createNotifcationCallback(context, product, productSentCallback));
        } else if (product.getName().equals("") && product.getBrands().equals("")) {
            apiService
                .saveProductWithoutNameAndBrands(product.getBarcode(), product.getLang(), product.getQuantity(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createNotifcationCallback(context, product, productSentCallback));
        } else if (product.getName().equals("") && product.getQuantity() == null) {
            apiService
                .saveProductWithoutNameAndQuantity(product.getBarcode(), product.getLang(), product.getBrands(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createNotifcationCallback(context, product, productSentCallback));
        } else if (product.getBrands().equals("") && product.getQuantity() == null) {
            apiService
                .saveProductWithoutBrandsAndQuantity(product.getBarcode(), product.getLang(), product.getName(), product.getUserId(), product.getPassword(), PRODUCT_API_COMMENT)
                .enqueue(createNotifcationCallback(context, product, productSentCallback));
        } else {
            apiService.saveProduct(product.getBarcode(), product.getLang(), product.getName(), product.getBrands(), product.getQuantity(), product
                .getUserId(), product.getPassword(), PRODUCT_API_COMMENT).enqueue(createNotifcationCallback(context, product, productSentCallback));
        }
    }

    public Callback<State> createNotifcationCallback(Context context, SendProduct product, OnProductSentCallback productSentCallback) {
        return new Callback<State>() {
            @Override
            public void onResponse(Call<State> call, Response<State> response) {
                onResponseCallForNotificationPostFunction(call, response, context, productSentCallback, product);
            }

            @Override
            public void onFailure(Call<State> call, Throwable t) {

                productSentCallback.onProductSentResponse(false);
            }
        };
    }
}
