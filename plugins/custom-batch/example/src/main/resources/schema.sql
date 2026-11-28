create table if not exists invoice (
    id bigint primary key,
    customer varchar(100) not null,
    tax_rate numeric(5, 4) not null,
    total numeric(12, 2) not null,
    recalculated_at timestamp
);

create table if not exists invoice_line (
    id bigserial primary key,
    invoice_id bigint not null references invoice (id),
    quantity int not null,
    unit_price numeric(10, 2) not null
);
