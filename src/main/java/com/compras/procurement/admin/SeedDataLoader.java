package com.compras.procurement.admin;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.RoleKit;
import com.compras.procurement.context.Asset;
import com.compras.procurement.context.Department;
import com.compras.procurement.context.Employee;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Loads the reference data (category templates, catalog, kits, mock HR and asset data) from
 * src/main/resources/seed/*.json. Editing those files and calling "Reset demo" is all it takes
 * to add a category or an item.
 */
@Component
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    private final MongoTemplate mongo;
    private final ObjectMapper objectMapper;

    public SeedDataLoader(MongoTemplate mongo, ObjectMapper objectMapper) {
        this.mongo = mongo;
        this.objectMapper = objectMapper;
    }

    /**
     * Reference data is configuration: the seed files are the source of truth, so it is reloaded on
     * every start. Transactional data (requests, orders, conversations) is never touched here.
     */
    @Override
    public void run(ApplicationArguments args) {
        loadReferenceData(false);
    }

    /** Drops and reloads all reference collections, the asset register included (demo reset). */
    public void loadReferenceData() {
        loadReferenceData(true);
    }

    /**
     * The asset register is reference data only until goods are received into it: on a normal start it is seeded
     * once, and assets checked in at receiving survive restarts.
     */
    public void loadReferenceData(boolean resetAssets) {
        load("seed/categories.json", CategoryDefinition.class);
        load("seed/catalog_items.json", CatalogItem.class);
        load("seed/role_kits.json", RoleKit.class);
        load("seed/employees.json", Employee.class);
        if (resetAssets || mongo.count(new org.springframework.data.mongodb.core.query.Query(), Asset.class) == 0) {
            load("seed/assets.json", Asset.class);
        }
        load("seed/departments.json", Department.class);
        load("seed/staff.json", com.compras.procurement.approval.StaffMember.class);
        log.info("Reference data loaded from classpath:seed/");
    }

    public <T> List<T> read(String path, Class<T> type) {
        CollectionType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, type);
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(in, listType);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }

    private <T> void load(String path, Class<T> type) {
        mongo.dropCollection(type);
        List<T> items = read(path, type);
        mongo.insertAll(items);
    }
}
