package org.openstatic.routeput.util;

import java.util.Set;
import java.util.StringTokenizer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.json.*;


public class JSONTools {

    /* Starting point for comparing JSONObjects, Arrays and plain old object 
       Will return true of the objects are equal or the first object contains
       all of the components of the second object
    */
    public static boolean matchesFilter(Object other, Object filter)
    {
        if (filter == other)
        {
            return true;
        } else if (filter instanceof JSONArray) {
            return matchesFilter((JSONArray) filter, other);
        } else if (filter instanceof JSONArray) {
            return matchesFilter((JSONObject) filter, other);
        } else {
            return filter.equals(other);
        }
    }

    public static boolean matchesFilter(Object array, JSONArray filter)
    {
        //System.err.println("Nested Array Filter: " + filter.toString());
        try {
            if (!(array instanceof JSONArray)) {
                return false;
            }
            JSONArray jArray = (JSONArray)array;
            List<Object> jArrayList = listJSONArray(jArray);
            Iterator<Object> iterator = filter.iterator();
            while (iterator.hasNext()) {
                Object valueFilter = iterator.next();
                if (valueFilter instanceof JSONObject) {
                    //System.err.println("We found a JSONObject in our array..");
                    JSONObject valueFilterJSONObject = (JSONObject)valueFilter;
                    if (jArrayList.stream().filter( (i) -> matchesFilter(i, valueFilterJSONObject) ).count() == 0)
                    {
                        return false;
                    }
                } else if (valueFilter instanceof JSONArray) {
                    //System.err.println("We found a JSONArray in our array..");
                    JSONArray valueFilterJSONArray = (JSONArray)valueFilter;
                    if (jArrayList.stream().filter( (i) -> matchesFilter(i, valueFilterJSONArray) ).count() == 0)
                    {
                        return false;
                    }
                } else if (!jArrayList.contains(valueFilter)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable exception) {
            return false;
        }
    }
    public static boolean matchesFilter(Object object, JSONObject filter)
    {
        //System.err.println("Nested Object Filter: " + filter.toString() + " against " + object.toString());
        try {
            if (!(object instanceof JSONObject)) {
                System.err.println("Not a JSONObject");
                return false;
            }
            JSONObject jObject = (JSONObject)object;
            Set<String> filterFieldSet = filter.keySet();
            Set<String> objectFieldSet = jObject.keySet();
            
            if (!objectFieldSet.containsAll(filterFieldSet)) {
                //System.err.println("Keyset differs");
                return false;
            }
            Iterator<String> iterator = filterFieldSet.iterator();
            while (iterator.hasNext()) {
                String name = iterator.next();
                Object valueFilter = filter.get(name);
                Object valueObject = jObject.get(name);
                if (valueFilter instanceof JSONObject) {
                    if (!matchesFilter(valueObject, ((JSONObject)valueFilter))) {
                        return false;
                    }
                } else if (valueFilter instanceof JSONArray) {
                    if (!matchesFilter(valueObject, ((JSONArray)valueFilter))) {
                        return false;
                    }
                } else if (!valueFilter.equals(valueObject)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable exception) {
            return false;
        }
    }
    
    /* Return the value inside a JSONObject by its path
       getPathValue( { "person":{"age":27} }, "person.age" ) returns 27
    */
    public static Object getPathValue(Object object, String path)
    {
        Object pointer = object;
        if (!"".equals(path) && path != null)
        {
            StringTokenizer st = new StringTokenizer(path, ".");
            while(st.hasMoreTokens())
            {
                String pathNext = st.nextToken();
                if (pathNext.startsWith("value("))
                {
                    pointer = evalStringMethod(null, pathNext);
                } else if (pointer == null) {
                    return null;
                } else if (pointer instanceof JSONObject) {
                    JSONObject joPointer = (JSONObject) pointer;
                    pointer = joPointer.opt(pathNext);
                } else if (pointer instanceof JSONArray) {
                    JSONArray jaPointer = (JSONArray) pointer;
                    pointer = jaPointer.opt(Integer.valueOf(pathNext).intValue());
                } else if (pointer instanceof java.lang.String) {
                    pointer = evalStringMethod(((String) pointer), pathNext);
                } else {
                    pointer = null;
                }
            }
        }
        return pointer;
    }

    public static Object evalStringMethod(String source, String method)
    {
        Object rv = source;
        if (!"".equals(method) && method != null)
        {
            final Pattern fnPattern = Pattern.compile("([a-z]+)\\((.*)\\)", Pattern.CASE_INSENSITIVE | Pattern.COMMENTS);
            final Matcher fnMatcher = fnPattern.matcher(method);
            while (fnMatcher.find()) {
                if (fnMatcher.groupCount() > 1)
                {
                    String methodName = fnMatcher.group(1);
                    String[] params = fnMatcher.group(2).split(",(?=(?:[^']*'[^']*')*[^']*$)");
                    for(int i = 0; i < params.length; i++)
                    {
                        params[i] = params[i].replaceAll("^'|'$", "");
                    }
                    if (methodName.equals("toUpperCase"))
                    {
                        rv = source.toUpperCase();
                    } else if (methodName.equals("toLowerCase")) {
                        rv = source.toLowerCase();
                    } else if (methodName.equals("contains") && params.length > 0) {
                        rv = source.contains(params[0]);
                    } else if (methodName.equals("replace") && params.length > 1) {
                        rv = source.replaceAll(Pattern.quote(params[0]), params[1]);
                    } else if (methodName.equals("value") && params.length > 0) {
                        rv = params[0];
                    } else if (methodName.equals("append") && params.length > 0) {
                        rv = source + params[0];
                    } else if (methodName.equals("prefix") && params.length > 0) {
                        rv = params[0] + source;
                    }
                }
            }
        }
        return rv;
    }

    /* return a list of the objects (unchanged) from a JSONArray */
    public static List<Object> listJSONArray(JSONArray array)
    {
        ArrayList<Object> al = new ArrayList<Object>(array.length());
        for(int i = 0; i < array.length(); i++)
        {
            al.add(i, array.get(i));
        }
        return al;
    }

    /* UNIT TESTS */
    public static void main(String[] args)
    {
        JSONObject filter = new JSONObject();
        filter.put("person", new JSONObject("{\"age\": 27}"));
        filter.put("times", new JSONArray("[ 43 ]"));
        filter.put("classes", new JSONArray("[ {\"level\": 102} ]"));

        JSONObject data = new JSONObject();
        data.put("person",  new JSONObject( "{\"age\": 27, \"gender\": \"male\"}" ));
        data.put("times", new JSONArray("[15, 25, 43, 22]"));
        data.put("place", "Ohio");
        
        JSONArray classes = new JSONArray();
        classes.put(new JSONObject("{\"level\": 101, \"name\": \"biology\"}"));
        classes.put(new JSONObject("{\"level\": 102, \"name\": \"math\"}"));
        classes.put(new JSONObject("{\"level\": 103, \"name\": \"science\"}"));
        data.put("classes",classes);

        System.err.println("Comparing: " + data.toString());
        System.err.println("Against Filter: " + filter.toString());
        System.err.println("");
        if (matchesFilter(data, filter))
        {
            System.err.println("matchesFilter() returned TRUE");
        } else {
            System.err.println("matchesFilter() returned FALSE");
        }
        System.err.println("");
        System.err.println("getPathValue person.gender (exists) = " + getPathValue(data, "person.gender"));
        System.err.println("getPathValue times (exists) = " + getPathValue(data, "times"));
        System.err.println("getPathValue value('hello,').append('world') (value) = " + getPathValue(data, "value('hello, ').append('world')"));
        System.err.println("getPathValue classes.0.name.replace('log','boss') (exists) = " + getPathValue(data, "classes.0.name.replace('log','boss')"));
        System.err.println("getPathValue times.0 (exists) = " + getPathValue(data, "times.0"));
        System.err.println("getPathValue times.44 (not exists) = " + getPathValue(data, "times.44"));
        System.err.println("getPathValue person.wonky (not exists) = " + getPathValue(data, "person.wonky"));
        System.err.println("getPathValue classes.0.name.contra() (not exists) = " + getPathValue(data, "classes.0.name.contra()"));

        System.err.println("getPathValue \"\" (blank) " + getPathValue(data, ""));
        System.err.println("getPathValue null (null) " + getPathValue(data, null));
    }
}
