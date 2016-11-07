package com.ericdmartell.simpleorm.actions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.reflections.Reflections;

import com.ericdmartell.simpleorm.associations.SimpleORMAssociation;
import com.ericdmartell.simpleorm.cache.SimpleORMCache;
import com.ericdmartell.simpleorm.factory.ActionFactory;
import com.ericdmartell.simpleorm.objects.SimpleORMLoadTemplate;
import com.ericdmartell.simpleorm.objects.SimpleORMObject;
import com.ericdmartell.simpleorm.utils.JDBCUtil;
import com.ericdmartell.simpleorm.utils.ReflectionUtils;
import com.ericdmartell.simpleorm.utils.SimpleORMException;

import gnu.trove.map.hash.THashMap;

public class AssociationLoad {
	private DataSource dataSource;
	private ActionFactory loadPathFactory;
	private SimpleORMLoadTemplate template;
	private SimpleORMCache cache;

	public AssociationLoad(DataSource dataSource, SimpleORMCache cache, ActionFactory loadPathFactory,
			SimpleORMLoadTemplate template) {
		this.dataSource = dataSource;
		this.cache = cache;
		this.loadPathFactory = loadPathFactory;
		this.template = template;
	}

	public List<SimpleORMObject> load(SimpleORMObject obj, SimpleORMAssociation association) {
		// Before even going to memcached, did this object come out of a
		// template? If so we have associations stored on the object itself.
		List<SimpleORMObject> ret = cache.getAssociatedObjectsForTemplate(obj, association);

		if (ret != null) {
			return ret;
		} else {
			// Get classes from the other side of the assoc.
			Class classToGet = obj.getClass() == association.class1() ? association.class2() : association.class1();

			// Load ids from the other side of the assoc and then bulk load the
			// objects themselves
			ret = loadPathFactory.getNewObjectLoad().load(classToGet, loadIds(obj, association));

			// If we're running in a template, cache the result on the object
			// itself.
			if (template != null) {
				cache.cacheAssociatedObjectsForTemplate(obj, association, ret);
			}
			return ret;
		}

	}

	private List<Long> loadIds(SimpleORMObject obj, SimpleORMAssociation association) {
		// Memcached
		List<Long> ret = cache.getAssociatedIds(obj, association);

		if (ret == null) {
			// Go to the database.
			if (association.type() == SimpleORMAssociation.MANY_TO_MANY) {
				ret = getManyToManyFromDB(obj, association);
			} else if (association.type() == SimpleORMAssociation.ONE_TO_MANY) {
				ret = getOneToManyFromDB(obj, association);
			}
			cache.setAssociatedIds(obj, association, ret, template);
		}
		return ret;
	}

	private List<Long> getOneToManyFromDB(SimpleORMObject obj, SimpleORMAssociation association) {
		List<Long> ret = new ArrayList<>();

		String query;
		if (obj.getClass() == association.class1()) {
			// We're on the one side of the one-many.
			query = "select id from " + association.class2().getSimpleName() + " where " + association.class2Column() + "="
					+ obj.id;
		} else {
			// We're on the many side of the one-many... The join data is right
			// on the object... But it might be dirty so we refresh
			obj = loadPathFactory.getNewObjectLoad().load(obj.getClass(), obj.id);
			long val = -1;
			val = (long) ReflectionUtils.getFieldValue(obj, association.class2Column());

			if (val != -1) {
				ret.add(val);
				return ret;
			} else {
				// Field must not exist in javaland...
				query = "select " + association.class2Column() + " from " + association.class2().getSimpleName()
						+ " where id = " + obj.id;
			}
		}

		// Builds out list of ids from query.
		Connection con = JDBCUtil.getConnection(dataSource);
		try {
			ResultSet rst = JDBCUtil.executeQuery(con, query);
			while (rst.next()) {
				ret.add(rst.getLong(1));
			}
		} catch (SQLException e) {
			throw new SimpleORMException(e);
		} finally {
			JDBCUtil.closeConnection(con);
		}

		return ret;
	}

	private List<Long> getManyToManyFromDB(SimpleORMObject obj, SimpleORMAssociation association) {
		List<Long> ret = new ArrayList<>();
		
		String tableName = association.class1().getSimpleName() + "_to_" + association.class2().getSimpleName();

		String whereColumn;
		String otherColumn;
		if (obj.getClass() == association.class1()) {
			whereColumn = association.class1().getSimpleName();
			otherColumn = association.class2().getSimpleName();
		} else {
			whereColumn = association.class2().getSimpleName();
			otherColumn = association.class1().getSimpleName();
		}
		Connection con = JDBCUtil.getConnection(dataSource);
		try {
			ResultSet rst = JDBCUtil.executeQuery(con,
					"select " + otherColumn + " from " + tableName + " where " + whereColumn + " = " + obj.id);
			while (rst.next()) {
				ret.add(rst.getLong(otherColumn));
			}
		} catch (SQLException e) {
			throw new SimpleORMException(e);
		} finally {
			JDBCUtil.closeConnection(con);
		}
		return ret;
	}

	
	//When we're deleting objects, or modifying objects with join columns, we need to dirty/delete assocs
	private Map<Class<SimpleORMObject>, List<SimpleORMAssociation>> classToLoadColumnAssocs = null;
	private Map<Class<SimpleORMObject>, List<SimpleORMAssociation>> classToAssocs = null;
	
	private void initializeClassToAssocs() {
		try {
			classToAssocs = new THashMap<>();
			classToLoadColumnAssocs = new THashMap<>();
			Reflections reflections = new Reflections("");
			List<Class<SimpleORMAssociation>> associations = new ArrayList(
					reflections.getSubTypesOf(SimpleORMAssociation.class));
			for (Class<SimpleORMAssociation> association : associations) {
				SimpleORMAssociation assoc = association.newInstance();
				
				if (!classToAssocs.containsKey(assoc.class1())) {
					classToAssocs.put(assoc.class1(), new ArrayList<>());
				}
				classToAssocs.get(assoc.class1()).add(assoc);
				
				if (!classToAssocs.containsKey(assoc.class2())) {
					classToAssocs.put(assoc.class2(), new ArrayList<>());
				}
				classToAssocs.get(assoc.class2()).add(assoc);
				
				if (assoc.type() == SimpleORMAssociation.ONE_TO_MANY) {
					if (!classToLoadColumnAssocs.containsKey(assoc.class2())) {
						classToLoadColumnAssocs.put(assoc.class2(), new ArrayList<>());
					}
					classToLoadColumnAssocs.get(assoc.class2()).add(assoc);
				}
			}
		} catch (InstantiationException | IllegalAccessException e) {
			throw new SimpleORMException(e);
		}
	}
	
	protected List loadWhereHasClassWithJoinColumn(Class clazz) {
		if (classToLoadColumnAssocs == null) {
			initializeClassToAssocs();
		}
		List ret = classToLoadColumnAssocs.get(clazz);
		if (ret == null) {
			return new ArrayList<>();
		}
		return ret;

	}

	

	public List loadWhereHasClass(Class<SimpleORMAssociation> clazz) {
		if (classToAssocs == null) {
			initializeClassToAssocs();
		}
		List ret = classToAssocs.get(clazz);
		if (ret == null) {
			return new ArrayList<>();
		}
		return ret;
	}
}
