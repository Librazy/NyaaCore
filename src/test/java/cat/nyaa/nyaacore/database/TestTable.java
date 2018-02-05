package cat.nyaa.nyaacore.database;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.Table;
import java.util.UUID;

@Table(name = "test1")
public class TestTable {

    @Id
    @Column
    public Long id;

    @Column
    public String string;

    @Column
    public UUID uuid;

    public UUID uuid_indirect;

    @Column(name = "uuid_indirect")
    public String getUuidIndirect() {
        return uuid_indirect.toString();
    }

    public void setUuidIndirect(String uuid_indirect) {
        this.uuid_indirect = UUID.fromString(uuid_indirect);
    }
}
