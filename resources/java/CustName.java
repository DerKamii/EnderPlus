import haven.ItemInfo;
import haven.L10N;

public class CustName
    implements ItemInfo.InfoFactory {
    public ItemInfo build(ItemInfo.Owner owner, Object[] args) {
	String name = (String) args[1];
	return new ItemInfo.Name(owner, L10N.label(name), name);
    }
}